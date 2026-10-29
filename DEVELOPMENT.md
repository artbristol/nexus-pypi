# Development notes

How the plugin works, how it is built and released, and how it has been tested. For installing
and using it, see the [README](README.md).

The plugin is a clean-room implementation, written from the nexus-public sources and the
observable behaviour of pypi.org (the [Simple API](https://peps.python.org/pep-0503/) and the
[PEPs](https://peps.python.org/) that define it) and the public `pip` client. It is not derived
from the PyPI support in Sonatype's Community or Pro editions.

## Building and testing

```sh
mvn package        # compiles, runs the unit tests, builds the jar
mvn verify         # also runs the end-to-end tests, against a Nexus with the jar and the real pypi.org
mvn verify -DskipITs   # ... without them
```

CI runs `mvn verify` on every push and pull request, one run at a time.

The unit tests cover what can be checked without a running Nexus, against something independent
where possible: the shape of pypi.org's Simple API output (PEP 503 HTML and PEP 691 JSON), real
Nexus MIME detection for the content types the proxy produces, the PEP 503 name normalisation and
PEP 427/376 file-name grammar, and what a streaming file download releases to its readers, and
when. Everything else is covered by the [end-to-end tests](#end-to-end-tests).

* The versions of the Nexus components, and of the libraries Nexus ships, come from Nexus'
  `nexus-common-components-formats-parent`, imported as a BOM (from Maven Central; no nexus-public
  checkout is needed). Nothing else is inherited from it: it brings Sonatype's own build (signing,
  javadocs, git hooks, AspectJ weaving, which none of this code needs).
* JDK 25 builds it, as it does Nexus; it compiles for Java 21.

## Targeting another Nexus version

The plugin uses Nexus internals (proxy facet support, content store, view handlers), which change
between releases. To build for another version, change `nexus.version` in `pom.xml`, fix
whatever no longer compiles, test against that Nexus version, and add a row to the compatibility
table in the README.

## How Nexus loads the plugin

Nexus Repository Core is a Spring Boot fat jar started by `PropertiesLauncher`, which adds the
jars in `loader.path` to the classpath. Nexus then component-scans the `org.sonatype.nexus` and
`com.sonatype.nexus` packages, so **the Java packages must stay under `org.sonatype.nexus`** for
the format to be found. Database tables (`pypi_*`) are created at startup by the `FormatStoreSupport`
DAOs.

The UI needs no changes: nexus-public already contains the repository form for `pypi-proxy`. The
REST endpoint `/v1/repositories/pypi/proxy` is provided by this plugin.

## Request handling

`pip` (and `uv`, `Poetry`) talk to the Simple API only:

| Client request | Purpose |
|---|---|
| `GET /simple/{project}/` | the project's index, PEP 503 HTML or PEP 691 JSON by `Accept` |
| `GET /simple/{project}/{file}` | a distribution file (a wheel, an sdist, ...) |
| `GET /simple/{project}/{file}.metadata` | a file's core metadata (PEP 658/714) |
| `HEAD ...` | the above, without the body |

`{project}` is the project name, normalised per PEP 503 (lower case, runs of `-_.` to `-`), so
`Requests`, `requests` and `requests-2` all address the same page. The repository's remote URL is
the Simple API root - the value of pip's `--index-url` - and a project's page is that root's path
plus `/{project}/`.

Index requests go through the proxy machinery (cache, staleness, cooperation). A file request first
resolves the file table (below), then serves the file from the cache or, if it is not cached yet,
streams it while it downloads (see [Files and metadata](#files-and-metadata)).

Nothing but `/simple/...` is proxied (anything else is 404): in particular PyPI's other endpoints
(the JSON API at `pypi.org/pypi/...`, `pypi.org/project/...`) are not.

### The index and the file table

A project's index page is fetched from the remote and parsed (`SimpleIndex`). Parsing does two
things:

* it **rewrites the file links** to point back at this repository - the `href` (HTML) or `url`
  (JSON) of every file becomes the bare file name, relative to the project page, so `pip` resolves
  it to `/simple/{project}/{file}`. The hash fragment (HTML) and the `hashes` object (JSON) are
  kept, and every `data-*` attribute (PEP 658/714 core metadata, PEP 700 size and upload time) is
  passed through unchanged, so the client still verifies what it downloads.
* it extracts the project's **file table**: for each file, where it is fetched from and the hash
  its bytes must match. The table is the unit the proxy uses to serve and verify files, and it is
  what makes a file request answerable without re-reading the index.

The index is served in the flavour the client asked for (PEP 691 JSON when `Accept` includes
`application/vnd.pypi.simple.v1+json`, HTML otherwise) and cached; the file table is cached too.
Both follow the *metadata* max age, since an index can change. Each flavour is fetched separately
and keeps the ETag and Last-Modified the remote sent with it, so a stale index is revalidated with
`If-None-Match`/`If-Modified-Since` rather than fetched again.

### Files and metadata

A file request looks the file up in the table. If the project has no table yet, the index is
fetched to build it (an unknown project yields an empty table, so the request is 404 and negatively
cached).

A file that is not cached yet is downloaded from where the table says it lives and **streamed to
the client while it downloads** (`StreamingDownload`): the body is written to a temporary file, of
which every chunk but the most recent one is released to the reading clients as the next one
arrives, and the last one only once the whole body has been checked - against the `sha256` the
index declared for the file, and against its declared size when the index declares one. No client
therefore ever receives a complete but corrupt file; when the index declares neither hash nor size
there is nothing to check the body against, and it is accepted as it arrives. A response is only
started for a download that has released its first chunk: one that fails earlier is answered with a
status (404 for content that does not match the index, 502 for a failed transfer) instead of a
broken transfer. Concurrent requests for the same file share one upstream download, which is
checked and stored as usual even if its clients go away. A file that does not match its declared
hash, or a body that is dropped or truncated, fails the download for every client reading it and is
not cached (Nexus answers the failed `GET` with 404 or 502, so a group can fall through), so a
clean retry re-fetches it. A `HEAD` never downloads.

A `.metadata` request (PEP 658/714) is served only for the files the index marks as having core
metadata, downloaded from the file URL plus `.metadata` and checked against the hash the index
declares for the metadata. Files and metadata are content-addressed and immutable, so they follow
the *content* max age; missing projects and files are negatively cached.

## Content type validation

Strict content type validation compares the detected type of content with the type its name
suggests, to keep e.g. an upstream error page from being cached. For distribution files that check
is both too weak and too strict: they are verified against the index's hashes before being stored,
and they are whatever their authors published under whatever name (a wheel is a zip, an sdist a
tarball, some an opaque archive), which strict validation would refuse to cache.
`PyPiContentValidator` therefore validates strictly only what the proxy produces itself: the index
pages (`text/html`, `application/vnd.pypi.simple.v1+json`), the file table (`application/json`) and
the core metadata (`text/plain`); distribution files are typed `application/octet-stream`.

## Components

One component per project release: namespace = empty, name = project, version = the release
version read from the file name (PEP 427 wheels and PEP 376/517 source distributions; best effort -
it only affects grouping, never serving), kind = `pypi`. Each distribution file is an asset of that
component. Index pages, file tables and core metadata are component-less assets (under `/.pypi/`
for the index and table, alongside the file for the metadata).

## Not supported (yet)

* Hosted and group repositories; uploads.
* PyPI endpoints other than the Simple API.
* Search customisation (assets are indexed by the default SQL search).

## End-to-end tests

`e2e/` holds a pytest suite that runs against a Nexus with the plugin installed and the real
pypi.org. It creates its own repositories (named `e2e-*`) and deletes them afterwards.

`mvn verify` runs it in the integration-test phases (`e2e/lifecycle.py`): it assembles a Nexus
Repository Core from Maven Central (the `nexus-base-overlay` zip, which is `bin/`, `etc/` and
`deploy/`, plus the `nexus-repository-core` jar), starts it with the new jar on a free port with
fresh data, builds a venv from the lockfile, runs the suite and stops Nexus. Everything is under
`target/e2e`, including Nexus' log. Python 3.13 must be on the path (or `-De2e.python=...`).

To run it against a Nexus of your own instead, e.g. while working on a test:

```sh
cd e2e
pip install -r requirements.txt
NEXUS_URL=http://localhost:8081 NEXUS_PASSWORD=... pytest
```

`NEXUS_USER` (default `admin`) must be allowed to create repositories and to change anonymous
access and SSRF protection, which the suite changes for the run and restores afterwards.
`requirements.txt` is a lockfile (generated from `requirements.in`, with hashes): the client
versions the suite was last run with. To move to newer clients, regenerate it as its header says,
run the suite, and keep this accurate.

Nexus does not talk to pypi.org directly but through a relay started by the suite (`relay.py`),
which forwards everything unchanged. Being the only way to pypi.org and to the file host, it keeps
the suite polite and gives the tests something to assert on:

* requests are at least 0.25 s apart, identified by a User-Agent pointing at this project, and
  limited to 400 requests and 100 MB per run; a 429 from pypi.org stops the run;
* only the tiny project `six` is used; a full run makes a few dozen requests and transfers a few
  MB;
* the Simple index names its files by absolute URLs on the file host
  (`files.pythonhosted.org`); the relay rewrites them to a prefix on itself (keeping any
  `#sha256=...` fragment a fragment), so the files and their `.metadata` are downloaded through,
  and accounted for by, the relay;
* every request is recorded, so tests check e.g. that a cached file needs no request at all, that
  a file was fetched from the file host exactly once, or that a `pip install` made no stray
  requests;
* faults can be injected into chosen responses - corrupt or truncated bodies, errors, dropped
  connections - which is how the failure handling is tested without a fake PyPI. Faults with a
  status do not reach pypi.org.

The relay must be reachable from Nexus: by default it listens on 127.0.0.1 (Nexus on the same
machine). Set `E2E_RELAY_HOST` otherwise.

The test modules: `test_pypi.py` (behaviour against the real pypi.org, including a real
`pip install` through the proxy) and `test_faults.py` (failure handling, with faults injected at
the relay).

## Releasing

CI (`.github/workflows/build.yml`) only provisions a machine and runs Maven: everything it does
runs the same from a laptop.

To release: set `<version>` in `pom.xml`, update the compatibility table in the README, commit and
push. Then either run the workflow (*Actions → build → Run workflow*), which builds and tests, then
releases the jar it tested; or, from a laptop with the GitHub CLI (`gh`) logged in, at that commit:

```sh
mvn -Pgithub-release deploy      # build, run all tests, release
```

Releasing creates the tag `v<version>` at the commit built, and a GitHub release with the jar and
`src/release/notes.md`; the tag is made from the version, so the two cannot disagree.
`mvn -Pgithub-release initialize exec:exec@github-release` releases a jar already built in `target/`,
and `-Drelease.gh=echo` shows the command instead of running it.
