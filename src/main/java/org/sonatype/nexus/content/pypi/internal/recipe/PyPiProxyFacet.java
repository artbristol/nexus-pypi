/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.pypi.internal.recipe;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import org.sonatype.nexus.content.pypi.PyPiContentFacet;
import org.sonatype.nexus.repository.cache.CacheController;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.content.facet.ContentProxyFacetSupport;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.httpclient.RemoteBlockedIOException;
import org.sonatype.nexus.repository.pypi.PyPiFileEntry;
import org.sonatype.nexus.repository.pypi.PyPiPaths;
import org.sonatype.nexus.repository.pypi.PyPiPaths.FileRef;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.sonatype.nexus.validation.ssrf.AntiSsrfService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.HttpClientUtils;
import jakarta.validation.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;
import static org.sonatype.nexus.content.pypi.PyPiContentFacet.FILE_TABLE_KIND;
import static org.sonatype.nexus.content.pypi.PyPiContentFacet.INDEX_KIND;
import static org.sonatype.nexus.content.pypi.PyPiContentFacet.METADATA_KIND;
import static org.sonatype.nexus.repository.pypi.internal.PyPiContentValidator.INDEX_HTML;
import static org.sonatype.nexus.repository.pypi.internal.PyPiContentValidator.INDEX_JSON;

/**
 * PyPI proxy facet.
 *
 * A project's index page (PEP 503 HTML, or PEP 691 JSON when the client asks for it) is fetched from the remote, its
 * file links are rewritten to point back at this repository, and it is cached; the files it describes are also parsed
 * into a file table. A distribution file (or its {@code .metadata}, PEP 658/714) is fetched from the URL the table
 * gives, checked against the hash the index declared for it, and cached. Index pages and file tables are subject to
 * the metadata max age; files and metadata, which are content-addressed and immutable, to the content max age.
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class PyPiProxyFacet
    extends ContentProxyFacetSupport
{
  private static final String JSON_ACCEPT = INDEX_JSON;

  private static final String HTML_ACCEPT = "text/html";

  private static final String JSON = "application/json";

  private final ObjectMapper objectMapper = new ObjectMapper();

  private AntiSsrfService antiSsrfService;

  enum Kind
  {
    INDEX,
    FILE,
    METADATA
  }

  /**
   * What the current proxy round-trip is about; set on the context before delegating to {@link #get(Context)} of the
   * superclass, which calls back into {@link #getCachedContent}, {@link #fetch} and {@link #store}.
   *
   * @param project     the project (normalised)
   * @param assetPath   path of the cached asset
   * @param upstreamUrl absolute URL to fetch from, for files and metadata (null for the index, which is derived)
   * @param json        whether the index is the PEP 691 JSON flavour
   * @param immutable   whether the upstream resource can never change
   * @param file        the file, for file and metadata targets
   * @param entry       the file's table entry, for file and metadata targets
   */
  record Target(
      Kind kind,
      String project,
      String assetPath,
      @Nullable String upstreamUrl,
      boolean json,
      boolean immutable,
      @Nullable FileRef file,
      @Nullable PyPiFileEntry entry)
  {
    static Target index(final String project, final boolean json) {
      return new Target(Kind.INDEX, project, PyPiPaths.indexAssetPath(project, json), null, json, false, null, null);
    }

    static Target file(final FileRef file, final PyPiFileEntry entry) {
      return new Target(Kind.FILE, file.project(), file.assetPath(), entry.url(), false, true, file, entry);
    }

    static Target metadata(final FileRef file, final PyPiFileEntry entry) {
      return new Target(Kind.METADATA, file.project(), file.metadataAssetPath(),
          entry.url() + PyPiPaths.METADATA_SUFFIX, false, true, file, entry);
    }
  }

  @Nullable
  @Override
  public Content get(final Context context) throws IOException {
    String path = context.getRequest().getPath();
    if (PyPiPaths.isIndex(path)) {
      String project = PyPiPaths.parseIndex(path).orElseThrow();
      setTarget(context, Target.index(project, clientWantsJson(context)));
      return super.get(context);
    }
    Optional<FileRef> file = PyPiPaths.parseFile(path);
    if (file.isPresent()) {
      FileRef ref = file.get();
      PyPiFileEntry entry = findEntry(ref.project(), ref.filename(), context);
      if (entry == null) {
        return null;
      }
      setTarget(context, Target.file(ref, entry));
      return super.get(context);
    }
    Optional<FileRef> metadata = PyPiPaths.parseMetadata(path);
    if (metadata.isPresent()) {
      FileRef ref = metadata.get();
      PyPiFileEntry entry = findEntry(ref.project(), ref.filename(), context);
      if (entry == null || !entry.coreMetadata()) {
        return null;
      }
      setTarget(context, Target.metadata(ref, entry));
      return super.get(context);
    }
    return null;
  }

  @Nullable
  private PyPiFileEntry findEntry(final String project, final String filename, final Context context)
      throws IOException
  {
    for (PyPiFileEntry entry : fileTable(project, context)) {
      if (entry.filename().equals(filename)) {
        return entry;
      }
    }
    return null;
  }

  // Index

  @Nullable
  @Override
  protected Content fetch(final Context context, @Nullable final Content stale) throws IOException {
    Target target = target(context);
    if (target.kind() != Kind.INDEX) {
      return super.fetch(context, stale);
    }
    Content upstream = super.fetch(context, stale);
    if (upstream == null) {
      return null;
    }
    String body;
    try (InputStream in = upstream.openInputStream()) {
      body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    finally {
      upstream.close();
    }
    String baseUrl = getRemoteUrl().resolve(getUrl(context)).toString();
    SimpleIndex.Parsed parsed;
    if (target.json()) {
      parsed = SimpleIndex.parseJson(objectMapper, baseUrl, objectMapper.readTree(body));
    }
    else {
      parsed = SimpleIndex.parseHtml(baseUrl, body);
    }
    storeFileTable(target.project(), parsed.files());
    String contentType = target.json() ? INDEX_JSON : INDEX_HTML;
    Content rewritten = new Content(new BytesPayload(parsed.rewritten().getBytes(StandardCharsets.UTF_8), contentType));
    CacheInfo cacheInfo = upstream.getAttributes().get(CacheInfo.class);
    if (cacheInfo != null) {
      rewritten.getAttributes().set(CacheInfo.class, cacheInfo);
    }
    return rewritten;
  }

  @Override
  protected String getUrl(final Context context) {
    Target target = target(context);
    if (target.kind() == Kind.INDEX) {
      return PyPiPaths.indexUpstreamPath(getRemoteUrl().getPath(), target.project());
    }
    return requireNonNull(target.upstreamUrl());
  }

  @Override
  protected HttpRequestBase buildFetchHttpRequest(final URI uri, final Context context, @Nullable final Content stale) {
    HttpRequestBase request = super.buildFetchHttpRequest(uri, context, stale);
    Target target = target(context);
    if (target.kind() == Kind.INDEX) {
      request.setHeader(HttpHeaders.ACCEPT, target.json() ? JSON_ACCEPT : HTML_ACCEPT);
    }
    return request;
  }

  // File table

  private List<PyPiFileEntry> fileTable(final String project, final Context context) throws IOException {
    Optional<Content> cached = content().get(PyPiPaths.fileTableAssetPath(project));
    if (cached.isPresent()) {
      return parseTable(cached.get());
    }
    // no table yet: fetch the project's index to build it
    return fetchFileTable(project, context);
  }

  private List<PyPiFileEntry> parseTable(final Content content) throws IOException {
    try (InputStream in = content.openInputStream()) {
      return objectMapper.readValue(
          in, objectMapper.getTypeFactory().constructCollectionType(List.class, PyPiFileEntry.class));
    }
  }

  /**
   * Fetches the project's index page directly and builds its file table, storing the table (and the rewritten index
   * page, so a following index request is served from the cache). An unknown project yields an empty table, so the
   * request is answered 404 and negatively cached.
   */
  private List<PyPiFileEntry> fetchFileTable(final String project, final Context context) throws IOException {
    URI uri = getRemoteUrl().resolve(PyPiPaths.indexUpstreamPath(getRemoteUrl().getPath(), project));
    validateRemoteHost(uri);
    HttpClient client = facet(HttpClientFacet.class).getHttpClient();
    HttpGet request = new HttpGet(uri);
    request.setHeader(HttpHeaders.ACCEPT, HTML_ACCEPT);
    HttpResponse response = execute(context, client, request);
    try {
      int status = response.getStatusLine().getStatusCode();
      if (status != HttpStatus.SC_OK) {
        log.debug("Upstream answered {} for {}", status, uri);
        return List.of();
      }
      String body;
      try (InputStream in = response.getEntity().getContent()) {
        body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      SimpleIndex.Parsed parsed = SimpleIndex.parseHtml(uri.toString(), body);
      storeFileTable(project, parsed.files());
      storeIndex(project, false, parsed.rewritten());
      return parsed.files();
    }
    finally {
      HttpClientUtils.closeQuietly(response);
    }
  }

  private void storeFileTable(final String project, final List<PyPiFileEntry> files) throws IOException {
    byte[] json = objectMapper.writeValueAsBytes(files);
    Content table = new Content(new BytesPayload(json, JSON));
    table.getAttributes().set(CacheInfo.class, metadataCacheController().current());
    content().putAsset(PyPiPaths.fileTableAssetPath(project), FILE_TABLE_KIND, table);
  }

  private void storeIndex(final String project, final boolean json, final String rewritten) throws IOException {
    Content index = new Content(new BytesPayload(rewritten.getBytes(StandardCharsets.UTF_8), json ? INDEX_JSON : INDEX_HTML));
    index.getAttributes().set(CacheInfo.class, metadataCacheController().current());
    content().putAsset(PyPiPaths.indexAssetPath(project, json), INDEX_KIND, index);
  }

  private CacheController metadataCacheController() {
    return cacheControllerHolder.getMetadataCacheController();
  }

  // ProxyFacetSupport callbacks

  @Nullable
  @Override
  protected Content getCachedContent(final Context context) throws IOException {
    return content().get(target(context).assetPath()).orElse(null);
  }

  @Override
  protected Content store(final Context context, final Content content) throws IOException {
    Target target = target(context);
    return switch (target.kind()) {
      case INDEX -> content().putAsset(target.assetPath(), INDEX_KIND, content);
      case FILE -> content().putFile(target.file(), content, target.entry() != null ? target.entry().sha256() : null);
      case METADATA ->
          content().putMetadata(target.file(), content,
              target.entry() != null ? target.entry().coreMetadataSha256() : null);
    };
  }

  @Override
  protected CacheController getCacheController(final Context context) {
    return target(context).immutable()
        ? cacheControllerHolder.getContentCacheController()
        : metadataCacheController();
  }

  @Override
  protected String getRequestKey(final Context context) {
    Target target = target(context);
    return target.kind() + ":" + target.assetPath();
  }

  /**
   * A HEAD carries no body to store, and must not download one just to report its size.
   */
  @Override
  protected boolean shouldSkipStoreForHead() {
    return true;
  }

  @Autowired
  @Override
  protected void configureAntiSsrfService(final AntiSsrfService antiSsrfService) {
    super.configureAntiSsrfService(antiSsrfService);
    this.antiSsrfService = antiSsrfService;
  }

  /**
   * The SSRF protection the regular proxy path applies, for the requests this facet makes directly.
   */
  private void validateRemoteHost(final URI uri) throws RemoteBlockedIOException {
    if (uri.getHost() == null || antiSsrfService == null) {
      return;
    }
    try {
      antiSsrfService.validateHost(uri.getHost());
    }
    catch (ValidationException e) {
      throw new RemoteBlockedIOException("Access to remote " + uri + " blocked: " + e.getMessage());
    }
  }

  // helpers

  private static void setTarget(final Context context, final Target target) {
    context.getAttributes().set(Target.class, target);
  }

  /**
   * The target set by {@link #get(Context)}; it is always set before any of the superclass callbacks run.
   */
  private static Target target(final Context context) {
    Target target = context.getAttributes().get(Target.class);
    requireNonNull(target, "no target set for " + (context.getRequest() == null ? null : context.getRequest().getPath()));
    return target;
  }

  /**
   * Whether the client asks for the PEP 691 JSON flavour of the simple index.
   */
  private static boolean clientWantsJson(final Context context) {
    String accept = context.getRequest().getHeaders().get(HttpHeaders.ACCEPT);
    return accept != null && accept.contains("application/vnd.pypi.simple.v1+json");
  }

  private PyPiContentFacet content() {
    return getRepository().facet(PyPiContentFacet.class);
  }
}
