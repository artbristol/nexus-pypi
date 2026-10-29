# PyPI proxy for Nexus Repository Core

A plugin that adds a **PyPI proxy** repository type to the open-source
[Sonatype Nexus Repository Core](https://github.com/sonatype/nexus-public), so that
`pip`, `uv`, `Poetry` and anything else that honours `--index-url` can download packages
through Nexus, with caching.

It is an independent project, not affiliated with or endorsed by Sonatype. Sonatype's Community
and Pro editions already include a PyPI (pypi) proxy; this plugin is for the open-source Core
build, which ships none.

## Compatibility

Each plugin release works with exactly one Nexus version:

| Plugin | Nexus Repository Core |
|---|---|
| 0.1.0 | 3.97.2-02 |
| 0.2.0 | 3.97.2-02 |

## Building

You can skip this and download the jar from the
[releases](https://github.com/artbristol/nexus-pypi/releases) instead.

Requires JDK 25 or later and Maven.

```sh
mvn package
```

The jar is `target/nexus-repository-pypi-<version>.jar`.

## Installing

1. Copy `nexus-repository-pypi-<version>.jar` into the `deploy/` directory of your Nexus
   installation.
2. Start Nexus with the JVM option `-Dloader.path=deploy`:

   ```sh
   INSTALL4J_ADD_VM_PARAMS="-Dloader.path=deploy" bin/nexus run
   ```

3. Create a repository: *Settings → Repositories → Create repository → pypi (proxy)*, with remote
   storage `https://pypi.org/simple`. Or use the REST API:

   ```sh
   curl -u admin -X POST -H 'Content-Type: application/json' \
     http://localhost:8081/service/rest/v1/repositories/pypi/proxy -d '{
       "name": "pypi-proxy", "online": true,
       "storage": {"blobStoreName": "default", "strictContentTypeValidation": true},
       "proxy": {"remoteUrl": "https://pypi.org/simple", "contentMaxAge": 1440, "metadataMaxAge": 1440},
       "negativeCache": {"enabled": true, "timeToLive": 1440},
       "httpClient": {"blocked": false, "autoBlock": true}}'
   ```

   The remote URL is the root of the [Simple API](https://peps.python.org/pep-0503/) - the value
   of pip's `--index-url`. The repository proxies that index and every file it names.

4. For a private index that needs credentials, add them to the repository as HTTP
   authentication. Everything those credentials can read becomes readable by anyone who can read
   the Nexus repository; restrict access to the repository accordingly.

## Using

Point clients at the repository's Simple API:

```sh
pip install --index-url http://localhost:8081/repository/pypi-proxy/simple requests
```

or, for the whole session,

```sh
export PIP_INDEX_URL=http://localhost:8081/repository/pypi-proxy/simple
```

`uv` and `Poetry` take the same URL as their index. Clients authenticate to Nexus, not to PyPI: a new
Nexus has anonymous access disabled, so the first request is answered 401 until you either allow anonymous
read access (*Administration → Security → Anonymous*) or put Nexus credentials into the URL:

```sh
pip install --index-url http://user:password@localhost:8081/repository/pypi-proxy/simple requests
```

Nexus rewrites the index so that every file link points back at the repository; `pip` downloads
the files through Nexus and verifies them against the hashes the index declares, exactly as it
would against PyPI.

## How it serves

* **The index** - `GET /simple/{project}/` is served as PEP 503 HTML, or as PEP 691 JSON when the
  client asks for it (`Accept: application/vnd.pypi.simple.v1+json`). The file links are rewritten
  to the repository; the declared hashes and every `data-*` attribute (PEP 658/714 core metadata,
  PEP 700 size and upload time) are kept, so clients still verify what they download.
* **The files** - `GET /simple/{project}/{file}` downloads the distribution file from where the
  index says it lives, checks it against the declared `sha256`, and caches it. An uncached file is
  streamed to the client while it downloads, every client reading it sharing one upstream download;
  no client ever receives a complete file that fails the declared check, and a file that fails it
  is not cached, so a clean retry re-fetches it.
* **The core metadata** - `GET /simple/{project}/{file}.metadata` (PEP 658/714) is served for the
  files the index marks as having it, and checked against the hash the index declares.
* **Caching** - index pages follow the *metadata* max age; files and metadata are content-addressed
  and immutable, so they follow the *content* max age. Missing projects and files are negatively
  cached.

## Limitations

* Proxy repositories only: no hosted or group repositories, no uploads.
* The Simple API is proxied only; PyPI's other endpoints (the JSON API, `pypi.org/project/...`)
  are not. Nor is the root of the index (`GET /simple/`, the list of all projects): clients always
  request a project's page, `/simple/{project}/`.

## License

[Eclipse Public License 1.0](LICENSE.txt), the license of Nexus Repository Core.

"Sonatype" and "Sonatype Nexus" are trademarks of Sonatype, Inc. This project is not affiliated
with Sonatype.
