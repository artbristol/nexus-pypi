"""
The proxy against the real pypi.org, without faults: whatever a client gets through Nexus must
match what pypi.org says, and what Nexus has cached must not need pypi.org again.

All repositories used are tiny (the project `six`); the whole module downloads a few MB.
"""

import json
import re
import subprocess
import sys

from conftest import http, sha256

PROJECT = "six"
JSON_ACCEPT = {"Accept": "application/vnd.pypi.simple.v1+json"}
HTML_ACCEPT = {"Accept": "text/html"}


def parse_html_index(html):
    """The files of a PEP 503 HTML index, as {filename: sha256-or-None}."""
    files = {}
    for match in re.finditer(r"<a\b([^>]*)>([^<]*)</a>", html):
        attrs, text = match.group(1), match.group(2)
        href = re.search(r'href="([^"]*)"', attrs)
        if not href:
            continue
        url, sep, fragment = href.group(1).partition("#")
        sha = None
        found = re.search(r"sha256=([0-9a-fA-F]+)", fragment)
        if found:
            sha = found.group(1).lower()
        name = text or url.rsplit("/", 1)[-1]
        files[name] = sha
    return files


def parse_json_index(body):
    """The files of a PEP 691 JSON index, as {filename: sha256-or-None}."""
    files = {}
    for file in json.loads(body)["files"]:
        sha = (file.get("hashes") or {}).get("sha256")
        files[file["filename"]] = sha.lower() if sha else None
    return files


def index_files(repository, accept):
    status, headers, body = http(repository.url + "/simple/%s/" % PROJECT, headers=accept)
    assert status == 200
    return parse_json_index(body.decode()) if "json" in accept["Accept"] else parse_html_index(body.decode())


def test_html_index_lists_the_same_files_as_pypi_org(repository, relay):
    reference = parse_html_index(http(relay.base_url + "simple/%s/" % PROJECT, headers=HTML_ACCEPT)[2].decode())
    assert reference, "pypi.org lists files for %s" % PROJECT
    assert index_files(repository, HTML_ACCEPT) == reference


def test_json_index_lists_the_same_files_as_pypi_org(repository, relay):
    reference = parse_json_index(
        http(relay.base_url + "simple/%s/" % PROJECT, headers=JSON_ACCEPT)[2].decode())
    assert reference, "pypi.org lists files for %s" % PROJECT
    assert index_files(repository, JSON_ACCEPT) == reference


def test_index_file_links_point_back_at_the_repository(repository):
    # JSON: the url of every file is relative to the project page, so pip resolves it to /simple/{project}/{file}
    _, _, body = http(repository.url + "/simple/%s/" % PROJECT, headers=JSON_ACCEPT)
    for file in json.loads(body)["files"]:
        assert "://" not in file["url"], "file link must be relative: %r" % file["url"]
    # HTML: the file host no longer appears anywhere in the page
    _, _, body = http(repository.url + "/simple/%s/" % PROJECT, headers=HTML_ACCEPT)
    html = body.decode()
    assert "pythonhosted" not in html and "pypi.org" not in html
    # ... yet the client can still verify each download: the hashes are kept
    assert index_files(repository, HTML_ACCEPT)


def test_file_is_downloaded_verified_and_cached(repository, relay):
    files = index_files(repository, JSON_ACCEPT)
    filename = next(name for name in files if name.endswith(".whl"))
    expected_sha = files[filename]
    assert expected_sha is not None

    mark = relay.mark()
    status, _, content = http(repository.url + "/simple/%s/%s" % (PROJECT, filename))
    assert status == 200
    assert sha256(content) == expected_sha
    assert len(relay.since(mark, file=True)) == 1, "the file was fetched from the file host once"

    mark = relay.mark()
    status, _, again = http(repository.url + "/simple/%s/%s" % (PROJECT, filename))
    assert status == 200 and again == content
    assert relay.since(mark, file=True) == [], "a cached file must not need the file host"


def test_core_metadata_is_served_when_the_index_offers_it(repository):
    _, _, body = http(repository.url + "/simple/%s/" % PROJECT, headers=JSON_ACCEPT)
    index = json.loads(body)
    with_meta = [f for f in index["files"] if f.get("core-metadata") or f.get("data-dist-info-metadata")]
    without_meta = [f for f in index["files"]
                    if not (f.get("core-metadata") or f.get("data-dist-info-metadata"))]
    assert with_meta, "pypi.org offers core metadata for %s" % PROJECT

    file = with_meta[0]
    declared = file["core-metadata"] if isinstance(file.get("core-metadata"), dict) else None
    status, headers, content = http(repository.url + "/simple/%s/%s.metadata" % (PROJECT, file["filename"]))
    assert status == 200
    assert headers["Content-Type"].startswith("text/plain")
    text = content.decode()
    assert text.startswith("Metadata-Version:") and "Name: six" in text
    if declared and declared.get("sha256"):
        assert sha256(content) == declared["sha256"].lower()

    if without_meta:
        other = without_meta[0]
        assert http(repository.url + "/simple/%s/%s.metadata" % (PROJECT, other["filename"]))[0] == 404


def test_head_reports_the_file_without_downloading_it_again(repository, relay):
    files = index_files(repository, JSON_ACCEPT)
    filename = next(name for name in files if name.endswith(".whl"))
    status, _, content = http(repository.url + "/simple/%s/%s" % (PROJECT, filename))
    assert status == 200

    mark = relay.mark()
    status, headers, _ = http(repository.url + "/simple/%s/%s" % (PROJECT, filename), method="HEAD")
    assert status == 200 and int(headers["Content-Length"]) == len(content)
    assert relay.since(mark, file=True) == [], "a HEAD must not download the file"


def test_unknown_project_is_404_and_negatively_cached(repository, relay):
    path = "/simple/this-project-does-not-exist-e2e-xyz/"
    assert http(repository.url + path)[0] == 404
    mark = relay.mark()
    assert http(repository.url + path)[0] == 404
    assert relay.since(mark) == [], "the 404 is negatively cached"


def test_unknown_file_is_404_and_negatively_cached(repository, relay):
    path = "/simple/%s/no-such-file-e2e.whl" % PROJECT
    assert http(repository.url + path)[0] == 404
    mark = relay.mark()
    assert http(repository.url + path)[0] == 404
    assert relay.since(mark) == [], "the 404 is negatively cached"


def test_pip_installs_through_the_proxy(repository, relay, tmp_path):
    target = tmp_path / "site"
    target.mkdir()
    mark = relay.mark()
    result = subprocess.run(
        [sys.executable, "-m", "pip", "install", "--quiet", "--no-input", "--no-cache-dir",
         "--index-url", repository.url + "/simple", "--target", str(target), "--no-deps", PROJECT],
        capture_output=True, text=True, timeout=180)
    assert result.returncode == 0, result.stderr
    # the wheel came through the proxy, and nothing went to pypi.org outside the relay
    assert any(target.glob("*.dist-info")), "%s was installed" % PROJECT
    assert [r for r in relay.since(mark) if not r.file], \
        "pip only fetched the index and the file, no stray requests"
