"""
A polite relay between Nexus and pypi.org.

Nexus' remote URL points at the relay, which forwards every Simple API request to pypi.org
unchanged, so the tests exercise the real protocol. Being the only way to pypi.org and to the
file host, it is also where the suite keeps its manners and its evidence:

* requests are spaced out and counted, and the run stops if it exceeds its request or byte
  budget, or if the upstream answers 429;
* every request is identified with a User-Agent pointing at this project;
* every request is recorded, so tests can assert e.g. that a file was fetched from the file
  host once;
* faults can be injected into chosen responses (corrupt or truncated bodies, stalls, errors).

The Simple index names its distribution files by absolute URLs on the file host
(files.pythonhosted.org). Those are rewritten to a prefix on the relay, so the files are
downloaded through, and accounted for by, the relay too. A fragment (the HTML
``#sha256=...`` hash) is kept a fragment, so the client can still verify what it downloads.
"""

import http.client
import http.server
import re
import socket
import threading
import time
import urllib.parse
from dataclasses import dataclass, field
from typing import Callable, List, Optional

UPSTREAM = "https://pypi.org"
USER_AGENT = "nexus-pypi-e2e (+https://github.com/artbristol/nexus-pypi)"
FILES_PREFIX = "/__files/"
HOP_BY_HOP = {"connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade",
              "proxy-authenticate", "proxy-authorization"}


class BudgetExceeded(Exception):
    pass


@dataclass
class Fault:
    """What to do to a matching response. Faults with a status never contact the upstream."""
    status: Optional[int] = None          # answer with this status instead
    drop: bool = False                    # close the connection without answering
    headers: dict = field(default_factory=dict)
    corrupt: bool = False                 # flip the last byte of the body
    truncate_after: Optional[int] = None  # send this many bytes of the body, then drop the connection
    stall_after: Optional[int] = None     # send this many bytes, then pause for stall_seconds
    stall_seconds: float = 0
    bytes_per_second: Optional[int] = None
    strip_headers: tuple = ()             # remove these headers from the upstream's response


@dataclass
class Rule:
    matches: Callable[["Request"], bool]
    fault: Fault
    times: int = 1


@dataclass
class Request:
    method: str
    path: str        # path and query on pypi.org, or of the file URL
    file: bool       # a distribution file, fetched through the relay's file prefix
    status: int = 0
    auth: bool = False  # sent with an Authorization header
    conditional: bool = False  # sent with an If-None-Match header


class Relay:
    def __init__(self, host="127.0.0.1", port=0, min_interval=0.25, max_requests=400,
                 max_bytes=100 * 1024 * 1024):
        self.min_interval = min_interval
        self.max_requests = max_requests
        self.max_bytes = max_bytes
        self.requests: List[Request] = []
        self.bytes = 0
        self.failure: Optional[str] = None
        self._rules: List[Rule] = []
        self._lock = threading.Lock()
        self._last_request = 0.0
        self._server = http.server.ThreadingHTTPServer((host, port), self._handler())
        self._server.daemon_threads = True
        port = self._server.server_address[1]
        self.base_url = "http://%s:%d/" % (host, port)
        threading.Thread(target=self._server.serve_forever, daemon=True).start()

    @property
    def file_url(self):
        return self.base_url

    def close(self):
        self._server.shutdown()

    # --- for tests

    def inject(self, fault: Fault, times=1, path=None, file=None, method=None):
        """Applies `fault` to the next `times` requests matching all the given criteria."""
        pattern = re.compile(path) if path else None

        def matches(r: Request):
            return ((pattern is None or pattern.search(r.path))
                    and (file is None or r.file == file)
                    and (method is None or r.method == method))

        rule = Rule(matches, fault, times)
        with self._lock:
            self._rules.append(rule)
        return rule

    def add_rule(self, rule: Rule):
        """For faults with conditions `inject` cannot express. `rule.matches` runs under the relay's lock."""
        with self._lock:
            self._rules.append(rule)
        return rule

    def clear_faults(self):
        with self._lock:
            self._rules.clear()

    def mark(self):
        """Position in the request log, to look at the requests made after it."""
        return len(self.requests)

    def since(self, mark, file=None, path=None):
        pattern = re.compile(path) if path else None
        return [r for r in self.requests[mark:]
                if (file is None or r.file == file) and (pattern is None or pattern.search(r.path))]

    # --- internals

    def _take_rule(self, request: Request) -> Optional[Fault]:
        with self._lock:
            for rule in self._rules:
                if rule.times > 0 and rule.matches(request):
                    rule.times -= 1
                    return rule.fault
        return None

    def _account(self, request: Request):
        with self._lock:
            if self.failure:
                raise BudgetExceeded(self.failure)
            if len(self.requests) >= self.max_requests:
                self.failure = "request budget of %d exceeded" % self.max_requests
                raise BudgetExceeded(self.failure)
            self.requests.append(request)
            wait = self._last_request + self.min_interval - time.monotonic()
            self._last_request = max(time.monotonic(), self._last_request + self.min_interval)
        if wait > 0:
            time.sleep(wait)

    def _count_bytes(self, n):
        with self._lock:
            self.bytes += n
            if self.bytes > self.max_bytes and not self.failure:
                self.failure = "byte budget of %d exceeded" % self.max_bytes

    def _rewrite(self, value: str) -> str:
        """Points the file host's URLs in the index body and in headers at the relay's file prefix.

        A fragment (the HTML ``#sha256=...`` hash) is kept a fragment, not folded into the path.
        """
        def one(url):
            fragment = ""
            if "#" in url:
                url, fragment = url.split("#", 1)
                fragment = "#" + fragment
            if url.startswith(UPSTREAM + "/"):
                return self.base_url + url[len(UPSTREAM) + 1:] + fragment
            if url.startswith("https://"):
                return self.file_url + FILES_PREFIX[1:] + urllib.parse.quote(url, safe="") + fragment
            return url
        return re.sub(r"https://[^\s<>,;\"']+", lambda m: one(m.group(0)), value)

    def _handler(self):
        relay = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass

            def do_GET(self):
                self._relay()

            def do_HEAD(self):
                self._relay()

            def do_POST(self):
                self._relay()

            def _relay(self):
                is_file = self.path.startswith(FILES_PREFIX)
                if is_file:
                    target = urllib.parse.unquote(self.path[len(FILES_PREFIX):])
                else:
                    target = UPSTREAM + self.path
                parsed = urllib.parse.urlsplit(target)
                request = Request(self.command, parsed.path + ("?" + parsed.query if parsed.query else ""),
                                  is_file, auth=self.headers.get("Authorization") is not None,
                                  conditional=self.headers.get("If-None-Match") is not None)
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                try:
                    relay._account(request)
                except BudgetExceeded as e:
                    return self._reply(599, str(e).encode())
                fault = relay._take_rule(request) or Fault()
                if fault.drop:
                    request.status = -1
                    return self._drop()
                if fault.status:
                    request.status = fault.status
                    return self._reply(fault.status, b"injected by the e2e relay", fault.headers)

                headers = {k: v for k, v in self.headers.items()
                           if k.lower() not in HOP_BY_HOP and k.lower() not in ("host", "content-length")}
                headers["User-Agent"] = (headers.get("User-Agent", "") + " " + USER_AGENT).strip()
                # the index body is rewritten, so it must arrive uncompressed
                headers = {k: v for k, v in headers.items() if k.lower() != "accept-encoding"}
                connection = http.client.HTTPSConnection(parsed.hostname, parsed.port or 443, timeout=60)
                try:
                    connection.request(self.command, parsed.path + ("?" + parsed.query if parsed.query else ""),
                                       body=body or None, headers=headers)
                    response = connection.getresponse()
                    request.status = response.status
                    if response.status == 429:
                        relay.failure = "pypi.org answered 429 (rate limited): stopping"
                    payload = response.read() if self.command != "HEAD" else b""
                    relay._count_bytes(len(payload))
                    # the Simple index (HTML or JSON) names its files by absolute URLs on the file host; point them
                    # at the relay so the files are downloaded through, and accounted for by, the relay. A file's own
                    # body is binary and is never rewritten.
                    if not is_file and payload:
                        payload = relay._rewrite(payload.decode(errors="replace")).encode()
                    self._respond(response, payload, fault, is_file)
                except OSError as e:
                    request.status = 502
                    self._reply(502, ("relay could not reach the upstream: %s" % e).encode())
                finally:
                    connection.close()

            def _respond(self, response, payload, fault, is_file):
                if fault.corrupt and payload:
                    payload = payload[:-1] + bytes([payload[-1] ^ 1])
                self.send_response(response.status)
                stripped = {h.lower() for h in fault.strip_headers}
                for name, value in response.getheaders():
                    lower = name.lower()
                    if lower in HOP_BY_HOP or lower == "content-length" or lower in stripped:
                        continue
                    if lower in ("location", "link"):
                        value = relay._rewrite(value)
                    self.send_header(name, value)
                length = int(response.getheader("Content-Length") or len(payload)) \
                    if self.command == "HEAD" else len(payload)
                self.send_header("Content-Length", str(length))
                self.end_headers()
                if self.command != "HEAD":
                    self._send_body(payload, fault)

            def _send_body(self, payload, fault):
                sent = 0
                stalled = False
                chunk = 16 * 1024
                while sent < len(payload):
                    if fault.truncate_after is not None and sent >= fault.truncate_after:
                        self._drop()
                        return
                    if fault.stall_after is not None and not stalled and sent >= fault.stall_after:
                        time.sleep(fault.stall_seconds)
                        stalled = True
                    end = min(len(payload), sent + chunk)
                    for limit in (fault.truncate_after, None if stalled else fault.stall_after):
                        if limit is not None and limit > sent:
                            end = min(end, limit)
                    try:
                        self.wfile.write(payload[sent:end])
                        self.wfile.flush()
                    except OSError:
                        return  # the client (Nexus) went away
                    if fault.bytes_per_second:
                        time.sleep((end - sent) / fault.bytes_per_second)
                    sent = end

            def _drop(self):
                self.close_connection = True
                try:
                    self.connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

            def _reply(self, status, body, headers=None):
                self.send_response(status)
                for name, value in (headers or {}).items():
                    self.send_header(name, value)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(body)

        return Handler
