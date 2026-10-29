/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.pypi;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Request paths of the PyPI Simple API (PEP 503), as used by {@code pip}, {@code uv} and {@code Poetry}.
 *
 * <ul>
 * <li>{@code /simple/{project}/} - a project's index page (PEP 503 HTML, or PEP 691 JSON by content negotiation)</li>
 * <li>{@code /simple/{project}/{file}} - a distribution file (a wheel, an sdist, ...)</li>
 * <li>{@code /simple/{project}/{file}.metadata} - a file's core metadata (PEP 658/714)</li>
 * </ul>
 * where {@code {project}} is the project name, normalised per PEP 503.
 *
 * The repository's remote URL is the Simple API root - the value of pip's {@code --index-url} - e.g.
 * {@code https://pypi.org/simple}. A project's index page is the remote URL's path plus {@code /{project}/}.
 */
public final class PyPiPaths
{
  public static final String SIMPLE_PREFIX = "/simple/";

  /**
   * Prefix of the assets that hold the proxy's own state (rewritten index pages and the parsed file tables), kept out
   * of the client-visible {@code /simple/} tree.
   */
  public static final String META_PREFIX = "/.pypi/";

  public static final String METADATA_SUFFIX = ".metadata";

  public static final String HTML_SUFFIX = "@html";

  public static final String JSON_SUFFIX = "@json";

  public static final String FILES_SUFFIX = "files";

  public static final String INDEX_REGEX = "^/simple/([^/]+)/?$";

  public static final String FILE_REGEX = "^/simple/([^/]+)/([^/]+)$";

  private static final Pattern INDEX_PATTERN = Pattern.compile(INDEX_REGEX);

  private static final Pattern FILE_PATTERN = Pattern.compile(FILE_REGEX);

  private static final Pattern METADATA_PATTERN = Pattern.compile("^/simple/([^/]+)/([^/]+)" + Pattern.quote(METADATA_SUFFIX) + "$");

  private PyPiPaths() {
  }

  /**
   * A distribution file of a project.
   */
  public record FileRef(String project, String filename)
  {
    public FileRef {
      requireNonNull(project);
      requireNonNull(filename);
    }

    /**
     * Path of the file's asset, e.g. {@code /simple/requests/requests-2.34.2-py3-none-any.whl}.
     */
    public String assetPath() {
      return SIMPLE_PREFIX + project + "/" + filename;
    }

    /**
     * Path of the file's core metadata asset, e.g. {@code /simple/requests/...whl.metadata}.
     */
    public String metadataAssetPath() {
      return assetPath() + METADATA_SUFFIX;
    }
  }

  /**
   * The PEP 503 normalisation of a project name: lower case, runs of {@code -_.} collapsed to {@code -}.
   */
  public static String normalizeProject(final String name) {
    return name.toLowerCase().replaceAll("[-_.]+", "-");
  }

  public static boolean isIndex(final String path) {
    return INDEX_PATTERN.matcher(path).matches();
  }

  /**
   * The project of an index request, or empty if the path is not one.
   */
  public static Optional<String> parseIndex(final String path) {
    Matcher matcher = INDEX_PATTERN.matcher(path);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(normalizeProject(matcher.group(1)));
  }

  /**
   * A distribution file request, or empty if the path is not one (an index, or a {@code .metadata} request).
   */
  public static Optional<FileRef> parseFile(final String path) {
    Matcher matcher = FILE_PATTERN.matcher(path);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String filename = matcher.group(2);
    if (filename.endsWith(METADATA_SUFFIX)) {
      return Optional.empty();
    }
    return Optional.of(new FileRef(normalizeProject(matcher.group(1)), filename));
  }

  /**
   * A core metadata request (PEP 658/714), the file name without its {@code .metadata} suffix.
   */
  public static Optional<FileRef> parseMetadata(final String path) {
    Matcher matcher = METADATA_PATTERN.matcher(path);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(new FileRef(normalizeProject(matcher.group(1)), matcher.group(2)));
  }

  /**
   * Asset path of a project's rewritten index page: {@code /.pypi/{project}/@html} or {@code .../@json}.
   */
  public static String indexAssetPath(final String project, final boolean json) {
    return META_PREFIX + project + "/" + (json ? JSON_SUFFIX : HTML_SUFFIX);
  }

  /**
   * Asset path of a project's parsed file table: {@code /.pypi/{project}/files}.
   */
  public static String fileTableAssetPath(final String project) {
    return META_PREFIX + project + "/" + FILES_SUFFIX;
  }

  /**
   * The path of a project's index page, relative to the origin, e.g. {@code /simple/requests/} for a remote of
   * {@code https://pypi.org/simple}. The Simple API root is the remote URL, so its path is the prefix of every page.
   */
  public static String indexUpstreamPath(final String remotePath, final String project) {
    String prefix = remotePath == null || remotePath.isEmpty() ? "/" : remotePath;
    if (!prefix.endsWith("/")) {
      prefix += "/";
    }
    return prefix + project + "/";
  }
}
