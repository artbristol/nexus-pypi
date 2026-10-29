"""
Faults injected by the relay, against the real pypi.org: a corrupted, truncated or failed
upstream response must not be cached or served as if it were valid, and a clean retry must work.
"""

import json

from conftest import http, sha256
from relay import Fault

PROJECT = "six"
JSON_ACCEPT = {"Accept": "application/vnd.pypi.simple.v1+json"}


def wheel(repository):
    """The newest wheel of the project, by name and declared sha256."""
    _, _, body = http(repository.url + "/simple/%s/" % PROJECT, headers=JSON_ACCEPT)
    files = json.loads(body)["files"]
    file = next(f for f in files if f["filename"].endswith(".whl"))
    return file["filename"], file["hashes"]["sha256"]


def test_corrupted_file_is_not_served_or_cached(repository, relay):
    filename, expected_sha = wheel(repository)

    relay.inject(Fault(corrupt=True), times=1, file=True)
    status, _, _ = http(repository.url + "/simple/%s/%s" % (PROJECT, filename))
    # a GET whose content fails validation is answered 404 by Nexus (so a group can fall through)
    assert status == 404, "a file that does not match its declared hash is rejected"

    status, _, content = http(repository.url + "/simple/%s/%s" % (PROJECT, filename))
    assert status == 200 and sha256(content) == expected_sha, "the clean retry is served and cached"


def test_truncated_file_is_not_served_or_cached(repository, relay):
    filename, expected_sha = wheel(repository)

    relay.inject(Fault(truncate_after=16), times=1, file=True)
    assert http(repository.url + "/simple/%s/%s" % (PROJECT, filename))[0] != 200, \
        "a truncated body is not a valid file"

    status, _, content = http(repository.url + "/simple/%s/%s" % (PROJECT, filename))
    assert status == 200 and sha256(content) == expected_sha


def test_upstream_error_on_the_index_is_not_cached(repository, relay):
    relay.inject(Fault(status=500), times=1, path=r"/simple/%s/" % PROJECT)
    # a 5xx from the remote is answered 503 by Nexus (the proxy could not serve it), and not cached
    assert http(repository.url + "/simple/%s/" % PROJECT)[0] == 503

    status, _, body = http(repository.url + "/simple/%s/" % PROJECT, headers=JSON_ACCEPT)
    assert status == 200 and json.loads(body)["files"], "the clean retry is served"


def test_upstream_outage_does_not_block_the_repository(repository, relay):
    # a 502 from the relay (it cannot reach pypi.org) surfaces as 503, and does not block the repository
    relay.inject(Fault(status=502), times=1, path=r"/simple/%s/" % PROJECT)
    assert http(repository.url + "/simple/%s/" % PROJECT)[0] == 503
    status, _, _ = http(repository.url + "/simple/%s/" % PROJECT, headers=JSON_ACCEPT)
    assert status == 200


def test_corrupted_index_is_not_cached(repository, relay):
    # the index ends in a newline, so a last-byte corruption would not break it; truncate instead
    relay.inject(Fault(truncate_after=50), times=1, path=r"/simple/%s/" % PROJECT)
    assert http(repository.url + "/simple/%s/" % PROJECT, headers=JSON_ACCEPT)[0] != 200, \
        "a truncated index is not a valid PEP 691 index and is not served"
    status, _, body = http(repository.url + "/simple/%s/" % PROJECT, headers=JSON_ACCEPT)
    assert status == 200 and json.loads(body)["files"], "the clean retry is served"
