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
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.io.InputStreamSupplier;
import org.sonatype.nexus.content.pypi.PyPiContentFacet;
import org.sonatype.nexus.repository.ETagHeaderUtils;
import org.sonatype.nexus.repository.MissingBlobException;
import org.sonatype.nexus.repository.cache.CacheController;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.ConfigurationFacet;
import org.sonatype.nexus.repository.content.facet.ContentProxyFacetSupport;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.httpclient.RemoteBlockedIOException;
import org.sonatype.nexus.repository.pypi.PyPiFileEntry;
import org.sonatype.nexus.repository.pypi.PyPiPaths;
import org.sonatype.nexus.repository.pypi.PyPiPaths.FileRef;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.sonatype.nexus.repository.view.payloads.StreamPayload;
import org.sonatype.nexus.validation.ssrf.AntiSsrfService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.HttpHeaders;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.DateUtils;
import org.apache.http.client.utils.HttpClientUtils;
import org.joda.time.DateTime;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.Pattern;
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
import static org.sonatype.nexus.repository.view.ContentTypes.APPLICATION_OCTET_STREAM;

/**
 * PyPI proxy facet.
 *
 * The Simple API root of the remote is the remote URL plus the remote index path (see {@link Config}).
 *
 * A project's index page (PEP 503 HTML, or PEP 691 JSON when the client asks for it) is fetched from the remote, its
 * file links are rewritten to point back at this repository, and it is cached; the files it describes are also parsed
 * into a file table. A distribution file (or its {@code .metadata}, PEP 658/714) is fetched from the URL the table
 * gives, checked against the hash the index declared for it, and cached. Index pages and file tables are subject to
 * the metadata max age; files and metadata, which are content-addressed and immutable, to the content max age.
 *
 * A distribution file that is not cached yet is streamed to the client while it downloads (see
 * {@link StreamingDownload}); it is stored as usual once it has been checked. The upstream validators of the index
 * pages are kept with the rewritten pages, so that revalidation asks the remote with If-None-Match/If-Modified-Since
 * instead of fetching them again.
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class PyPiProxyFacet
    extends ContentProxyFacetSupport
{
  private static final String JSON_ACCEPT = INDEX_JSON;

  private static final String HTML_ACCEPT = "text/html";

  private static final String JSON = "application/json";

  /**
   * The configuration section of the PyPI settings, as the repository form of the UI saves them.
   */
  public static final String CONFIG_KEY = "pypi";

  /**
   * The PyPI settings of a proxy repository.
   */
  public static class Config
  {
    /**
     * Path of the Simple API under the remote URL: {@code /simple} for pypi.org and the indexes laid out like it, or
     * empty for an index served at the root of its remote URL. The UI allows only these two.
     */
    @Pattern(regexp = "^(/simple/?)?$", message = "Index path must be \"/simple\" (standard) or empty (root path)")
    public String indexPath = PyPiPaths.DEFAULT_INDEX_PATH;
  }

  private Config pypiConfig;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private AntiSsrfService antiSsrfService;

  /**
   * File downloads in progress, by asset path.
   */
  private final ConcurrentMap<String, CompletableFuture<StreamingDownload>> downloads = new ConcurrentHashMap<>();

  private ExecutorService downloadExecutor;

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
      Content streamed = streamFile(context, ref, entry);
      if (streamed != null) {
        return streamed;
      }
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

  // Streaming of files that are not cached yet

  /**
   * Serves a distribution file that is not cached yet while it downloads, rather than after: wheels of large projects
   * are many GB, and {@code pip} gives up after about 15 seconds without data. Concurrent requests for the same file
   * share one upstream download, which completes and is cached even if the clients go away.
   *
   * @return {@code null} if the file cannot be streamed, to fall back to the regular proxy path
   * @see StreamingDownload
   */
  @Nullable
  private Content streamFile(final Context context, final FileRef file, final PyPiFileEntry entry) throws IOException {
    if (HttpMethods.HEAD.equals(context.getRequest().getAction())) {
      return null; // a HEAD must not download the file
    }
    if (cachedFile(context) != null) {
      return null; // cached: the regular path serves it, and revalidates it when stale
    }
    StreamingDownload download = joinOrStartDownload(context, file, entry);
    if (download == null) {
      return null;
    }
    InputStreamSupplier readers = () -> {
      InputStream in = download.openReader();
      if (in != null) {
        return in;
      }
      // finished between the lookup and now
      Optional<Content> cached = content().get(file.assetPath());
      if (cached.isEmpty()) {
        throw new IOException("Download of " + file.assetPath() + " failed");
      }
      return cached.get().openInputStream();
    };
    Content streamed = new Content(new StreamPayload(readers, sizeOf(entry), APPLICATION_OCTET_STREAM));
    if (download.etag() != null) {
      streamed.getAttributes().set(Content.CONTENT_ETAG, download.etag());
    }
    if (download.lastModified() != null) {
      streamed.getAttributes().set(Content.CONTENT_LAST_MODIFIED, download.lastModified());
    }
    return streamed;
  }

  /**
   * The size to report for the file, as the index declared it; unknown if it declared none.
   */
  private static long sizeOf(final PyPiFileEntry entry) {
    return entry.size() != null ? entry.size() : Payload.UNKNOWN_SIZE;
  }

  /**
   * The cached file, or null if there is none; a blob that has gone missing counts as none, so the file is downloaded
   * again, as the regular proxy path would.
   */
  @Nullable
  private Content cachedFile(final Context context) throws IOException {
    try {
      return getCachedContent(context);
    }
    catch (MissingBlobException e) {
      return null;
    }
  }

  /**
   * Joins the download of the file in progress, or starts one. The slot is taken before contacting upstream, so
   * concurrent requests never open a second upstream connection; they wait for the first one's response instead.
   *
   * @return {@code null} if the download could not be started, to fall back to the regular proxy path
   */
  @Nullable
  private StreamingDownload joinOrStartDownload(
      final Context context,
      final FileRef file,
      final PyPiFileEntry entry) throws IOException
  {
    CompletableFuture<StreamingDownload> slot = new CompletableFuture<>();
    CompletableFuture<StreamingDownload> existing = downloads.putIfAbsent(file.assetPath(), slot);
    if (existing != null) {
      return joinDownload(existing);
    }
    try {
      if (cachedFile(context) != null) {
        return null; // stored while the slot was being taken
      }
      StreamingDownload download = startDownload(context, file, entry, slot);
      if (download == null) {
        return null;
      }
      slot.complete(download);
      download.awaitReadable();
      return download;
    }
    catch (IOException | RuntimeException e) {
      slot.completeExceptionally(e);
      throw e;
    }
    finally {
      downloads.remove(file.assetPath(), slot);
      slot.complete(null); // a no-op unless the leader failed to complete it; joiners then use the regular path
    }
  }

  @Nullable
  private StreamingDownload joinDownload(final CompletableFuture<StreamingDownload> slot) throws IOException {
    StreamingDownload download;
    try {
      download = slot.get();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for upstream", e);
    }
    catch (ExecutionException e) {
      // the request that started it failed before any download began; the regular path handles that
      return null;
    }
    if (download == null) {
      return null;
    }
    download.awaitReadable();
    return download;
  }

  /**
   * Requests the file in the request thread, so upstream errors reach this client, and hands the body over to a
   * background download.
   *
   * @return {@code null} if the remote did not answer 200, to fall back to the regular proxy path, which answers the
   *      status the way it always does
   */
  @Nullable
  private StreamingDownload startDownload(
      final Context context,
      final FileRef file,
      final PyPiFileEntry entry,
      final CompletableFuture<StreamingDownload> slot) throws IOException
  {
    HttpClient client = facet(HttpClientFacet.class).getHttpClient();
    URI uri = URI.create(requireNonNull(entry.url()));
    validateRemoteHost(uri);
    HttpGet request = new HttpGet(uri);
    HttpResponse response = execute(context, client, request);
    int status = response.getStatusLine().getStatusCode();
    if (status != HttpStatus.SC_OK) {
      log.debug("Upstream answered {} for {}, not streaming", status, uri);
      request.abort();
      return null;
    }

    StreamingDownload download;
    try {
      download = new StreamingDownload(Files.createTempFile("pypi-", ".part"), file.assetPath(),
          entry.sha256(), entry.size(), etagOf(response), lastModifiedOf(response));
    }
    catch (IOException e) {
      request.abort();
      throw e;
    }
    CacheInfo cacheInfo = getCacheController(context).current();
    downloadExecutor.execute(() -> runDownload(file, download, request, response, cacheInfo, slot));
    return download;
  }

  private void runDownload(
      final FileRef file,
      final StreamingDownload download,
      final HttpGet request,
      final HttpResponse response,
      final CacheInfo cacheInfo,
      final CompletableFuture<StreamingDownload> slot)
  {
    boolean complete = false;
    try {
      HttpEntity entity = response.getEntity();
      if (entity == null) {
        throw new IOException("Upstream answered " + request.getURI() + " without a body");
      }
      download.download(entity.getContent());
      complete = true;
      try {
        content().putFile(file, download.file(), APPLICATION_OCTET_STREAM, cacheInfo,
            download.etag(), download.lastModified());
        log.debug("Downloaded and stored {} in {}", file.assetPath(), getRepository().getName());
      }
      finally {
        // the bytes are verified; clients finish even if storing failed and the file is not cached
        download.finish();
      }
    }
    catch (Exception e) {
      download.fail(e instanceof IOException io ? io : e);
      log.warn("Download of {} into {} failed: {}", file.assetPath(), getRepository().getName(), e.toString());
    }
    finally {
      downloads.remove(file.assetPath(), slot);
      try {
        download.delete();
      }
      catch (IOException e) {
        log.warn("Could not delete {}: {}", download.file(), e.toString());
      }
      if (!complete) {
        // closing would drain the rest of a possibly multi-GB body
        request.abort();
      }
      HttpClientUtils.closeQuietly(response);
    }
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
    if (parsed.unparsedAnchors() > 0) {
      log.warn("Could not parse {} of the anchors of the index of {}; their links are left as the remote sent them",
          parsed.unparsedAnchors(), target.project());
    }
    storeFileTable(target.project(), parsed.files());
    String contentType = target.json() ? INDEX_JSON : INDEX_HTML;
    Content rewritten = new Content(new BytesPayload(parsed.rewritten().getBytes(StandardCharsets.UTF_8), contentType));
    CacheInfo cacheInfo = upstream.getAttributes().get(CacheInfo.class);
    if (cacheInfo != null) {
      rewritten.getAttributes().set(CacheInfo.class, cacheInfo);
    }
    // the upstream validators, so that revalidation sends If-None-Match/If-Modified-Since instead of fetching the
    // whole index again. Each flavour is fetched separately, and carries its own.
    rewritten.getAttributes().set(Content.CONTENT_ETAG, upstream.getAttributes().get(Content.CONTENT_ETAG));
    rewritten.getAttributes()
        .set(Content.CONTENT_LAST_MODIFIED, upstream.getAttributes().get(Content.CONTENT_LAST_MODIFIED));
    return rewritten;
  }

  @Override
  protected String getUrl(final Context context) {
    Target target = target(context);
    if (target.kind() == Kind.INDEX) {
      return indexUpstreamPath(target.project());
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
   * request is answered 404 and negatively cached; any other failed response fails the request, which is answered
   * with a status but not negatively cached, as an index request through the regular proxy path would be.
   */
  private List<PyPiFileEntry> fetchFileTable(final String project, final Context context) throws IOException {
    URI uri = getRemoteUrl().resolve(indexUpstreamPath(project));
    validateRemoteHost(uri);
    HttpClient client = facet(HttpClientFacet.class).getHttpClient();
    HttpGet request = new HttpGet(uri);
    request.setHeader(HttpHeaders.ACCEPT, HTML_ACCEPT);
    HttpResponse response = execute(context, client, request);
    try {
      int status = response.getStatusLine().getStatusCode();
      if (status == HttpStatus.SC_NOT_FOUND) {
        log.debug("Upstream answered 404 for {}; no such project", uri);
        return List.of();
      }
      if (status != HttpStatus.SC_OK) {
        log.debug("Upstream answered {} for {}", status, uri);
        mayThrowProxyServiceException(response); // 401/402/407/5xx, as the index request itself would be
        throw new IOException("Upstream answered " + status + " for " + uri);
      }
      String body;
      try (InputStream in = response.getEntity().getContent()) {
        body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      SimpleIndex.Parsed parsed = SimpleIndex.parseHtml(uri.toString(), body);
      if (parsed.unparsedAnchors() > 0) {
        log.warn("Could not parse {} of the anchors of the index of {}; their links are left as the remote sent them",
            parsed.unparsedAnchors(), project);
      }
      storeFileTable(project, parsed.files());
      storeIndex(project, false, parsed.rewritten(), etagOf(response), lastModifiedOf(response));
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

  private void storeIndex(
      final String project,
      final boolean json,
      final String rewritten,
      @Nullable final String etag,
      @Nullable final DateTime lastModified) throws IOException
  {
    Content index = new Content(new BytesPayload(rewritten.getBytes(StandardCharsets.UTF_8), json ? INDEX_JSON : INDEX_HTML));
    index.getAttributes().set(CacheInfo.class, metadataCacheController().current());
    // the upstream validators, so that revalidation sends If-None-Match/If-Modified-Since instead of fetching the
    // whole index again
    if (etag != null) {
      index.getAttributes().set(Content.CONTENT_ETAG, etag);
    }
    if (lastModified != null) {
      index.getAttributes().set(Content.CONTENT_LAST_MODIFIED, lastModified);
    }
    content().putAsset(PyPiPaths.indexAssetPath(project, json), INDEX_KIND, index);
  }

  /**
   * The upstream ETag of a response, unquoted, or null if it sent none. Some remotes send the header lower case.
   */
  @Nullable
  private static String etagOf(final HttpResponse response) {
    Header etagHeader = response.getLastHeader(HttpHeaders.ETAG);
    if (etagHeader == null) {
      etagHeader = response.getLastHeader("etag");
    }
    return etagHeader == null ? null : ETagHeaderUtils.extract(etagHeader.getValue());
  }

  /**
   * The upstream Last-Modified of a response, or null if it sent none or sent one that cannot be parsed.
   */
  @Nullable
  private DateTime lastModifiedOf(final HttpResponse response) {
    Header header = response.getLastHeader(HttpHeaders.LAST_MODIFIED);
    if (header == null) {
      return null;
    }
    try {
      return new DateTime(DateUtils.parseDate(header.getValue()).getTime());
    }
    catch (Exception e) { // NOSONAR the parser throws a private exception type
      log.debug("Could not parse Last-Modified '{}' as a date", header.getValue());
      return null;
    }
  }

  private String indexUpstreamPath(final String project) {
    return PyPiPaths.indexUpstreamPath(getRemoteUrl().getPath(), pypiConfig.indexPath, project);
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

  @Override
  protected void doValidate(final Configuration configuration) throws Exception {
    super.doValidate(configuration);
    facet(ConfigurationFacet.class).validateSection(configuration, CONFIG_KEY, Config.class);
  }

  @Override
  protected void doConfigure(final Configuration configuration) throws Exception {
    super.doConfigure(configuration);
    pypiConfig = facet(ConfigurationFacet.class).readSection(configuration, CONFIG_KEY, Config.class);
  }

  @Override
  protected void doStart() {
    super.doStart();
    String name = "pypi-download-" + getRepository().getName();
    downloadExecutor = Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, name);
      thread.setDaemon(true);
      return thread;
    });
  }

  @Override
  protected void doStop() throws Exception {
    downloadExecutor.shutdownNow();
    super.doStop();
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
