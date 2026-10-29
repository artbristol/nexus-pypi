/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.pypi.internal.recipe;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.sonatype.nexus.repository.pypi.PyPiFileEntry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static java.util.Objects.requireNonNull;

/**
 * Parses a PyPI Simple API index (PEP 503 HTML or PEP 691 JSON) and rewrites the file links to point back at the
 * repository, so that the distribution files - and their {@code .metadata} (PEP 658/714) - are downloaded through, and
 * cached by, the repository instead of going straight to the file host.
 *
 * The rewrite changes only the file links: every file's href (HTML) or {@code url} (JSON) becomes the bare file name,
 * relative to the project page, so {@code pip} resolves it to {@code /simple/{project}/{file}}. The hash fragment
 * (HTML) and the {@code hashes} object (JSON) are kept, so the client still verifies what it downloads; every data
 * attribute (PEP 503/658/700/714) is passed through unchanged.
 *
 * The parsed files also become the project's file table: where each file is fetched from, and the hash its bytes must
 * match before they are stored.
 */
public final class SimpleIndex
{
  private static final Pattern ANCHOR = Pattern.compile("<a\\b([^>]*)>([^<]*)</a>");

  private static final Pattern ATTRIBUTE = Pattern.compile("\\s(\\S+?)=\"([^\"]*)\"");

  private static final Pattern FRAGMENT_HASH = Pattern.compile("([A-Za-z0-9]+)=([0-9a-fA-F]+)");

  private SimpleIndex() {
  }

  /**
   * The result of parsing and rewriting an index page.
   *
   * @param rewritten the index page with its file links pointing at the repository
   * @param files     the files the index describes
   */
  public record Parsed(String rewritten, List<PyPiFileEntry> files) {
  }

  /**
   * The base URL the index page was fetched from, used to resolve relative file links.
   */
  public static Parsed parseHtml(final String baseUrl, final String html) {
    requireNonNull(html);
    URI base = URI.create(baseUrl);
    List<PyPiFileEntry> files = new ArrayList<>();
    StringBuilder out = new StringBuilder(html.length());
    Matcher matcher = ANCHOR.matcher(html);
    int last = 0;
    while (matcher.find()) {
      out.append(html, last, matcher.start());
      String attrs = matcher.group(1);
      String text = matcher.group(2);
      String href = attribute(attrs, "href");
      if (href == null) {
        out.append(matcher.group());
      }
      else {
        int fragmentAt = href.indexOf('#');
        String urlPart = fragmentAt < 0 ? href : href.substring(0, fragmentAt);
        String fragment = fragmentAt < 0 ? null : href.substring(fragmentAt + 1);
        String filename = !text.isEmpty() ? text : lastPathSegment(urlPart);
        Map<String, String> hashes = fragmentHashes(fragment);
        files.add(new PyPiFileEntry(
            filename,
            resolve(base, urlPart),
            hashes,
            unescape(attribute(attrs, "data-requires-python")),
            attribute(attrs, "data-yanked") != null,
            attribute(attrs, "data-yanked"),
            null,
            null,
            attribute(attrs, "data-core-metadata") != null || attribute(attrs, "data-dist-info-metadata") != null,
            coreMetadataSha256Of(attrs),
            unescape(attribute(attrs, "data-provenance"))));
        String newHref = filename + (fragment == null ? "" : "#" + fragment);
        out.append("<a ").append(replaceAttribute(attrs, "href", newHref)).append('>').append(text).append("</a>");
      }
      last = matcher.end();
    }
    out.append(html, last, html.length());
    return new Parsed(out.toString(), files);
  }

  public static Parsed parseJson(final ObjectMapper mapper, final String baseUrl, final JsonNode index)
      throws IOException
  {
    requireNonNull(index);
    URI base = URI.create(baseUrl);
    ObjectNode root = (ObjectNode) index;
    JsonNode filesNode = root.get("files");
    if (!(filesNode instanceof ArrayNode files)) {
      throw new IOException("Not a PEP 691 simple index: no \"files\" array");
    }
    List<PyPiFileEntry> entries = new ArrayList<>(files.size());
    for (JsonNode file : files) {
      ObjectNode node = (ObjectNode) file;
      String filename = node.path("filename").asText(null);
      String url = node.path("url").asText(null);
      if (filename == null || url == null) {
        continue;
      }
      Map<String, String> hashes = new LinkedHashMap<>();
      node.path("hashes").fields().forEachRemaining(e -> hashes.put(e.getKey(), e.getValue().asText()));
      JsonNode yanked = node.get("yanked");
      boolean isYanked;
      String yankReason = null;
      if (yanked != null && yanked.isTextual()) {
        isYanked = true;
        yankReason = yanked.asText();
      }
      else {
        isYanked = yanked != null && yanked.asBoolean(false);
      }
      JsonNode coreMetadata = node.get("core-metadata");
      JsonNode distInfoMetadata = node.get("data-dist-info-metadata");
      entries.add(new PyPiFileEntry(
          filename,
          resolve(base, url),
          hashes,
          textOrNull(node.get("requires-python")),
          isYanked,
          yankReason,
          node.hasNonNull("size") ? node.get("size").asLong() : null,
          textOrNull(node.get("upload-time")),
          hasMetadata(coreMetadata) || hasMetadata(distInfoMetadata),
          metadataSha256(coreMetadata) != null ? metadataSha256(coreMetadata) : metadataSha256(distInfoMetadata),
          textOrNull(node.get("provenance"))));
      node.put("url", filename);
    }
    return new Parsed(mapper.writeValueAsString(root), entries);
  }

  // helpers

  private static String resolve(final URI base, final String url) {
    if (url.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
      return url;
    }
    return base.resolve(url).toString();
  }

  @Nullable
  private static String attribute(final String attrs, final String name) {
    Matcher matcher = ATTRIBUTE.matcher(attrs);
    while (matcher.find()) {
      if (matcher.group(1).equals(name)) {
        return matcher.group(2);
      }
    }
    return null;
  }

  private static String replaceAttribute(final String attrs, final String name, final String value) {
    StringBuilder out = new StringBuilder();
    Matcher matcher = ATTRIBUTE.matcher(attrs);
    int last = 0;
    while (matcher.find()) {
      out.append(attrs, last, matcher.start());
      if (matcher.group(1).equals(name)) {
        out.append(' ').append(name).append("=\"").append(escape(value)).append('"');
        last = matcher.end();
      }
    }
    out.append(attrs, last, attrs.length());
    return out.toString();
  }

  private static Map<String, String> fragmentHashes(@Nullable final String fragment) {
    Map<String, String> hashes = new LinkedHashMap<>();
    if (fragment != null) {
      Matcher matcher = FRAGMENT_HASH.matcher(fragment);
      while (matcher.find()) {
        hashes.put(matcher.group(1).toLowerCase(), matcher.group(2).toLowerCase());
      }
    }
    return hashes;
  }

  private static String lastPathSegment(final String url) {
    int slash = url.lastIndexOf('/');
    return slash < 0 ? url : url.substring(slash + 1);
  }

  private static String textOrNull(@Nullable final JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : null;
  }

  /**
   * PEP 658/714: the {@code core-metadata} (or legacy {@code data-dist-info-metadata}) field is either a boolean or an
   * object carrying the metadata's hash.
   */
  private static boolean hasMetadata(@Nullable final JsonNode node) {
    return node != null && !node.isNull() && (node.isBoolean() ? node.asBoolean() : true);
  }

  @Nullable
  private static String metadataSha256(@Nullable final JsonNode node) {
    if (node != null && node.isObject()) {
      JsonNode sha = node.get("sha256");
      return sha != null && sha.isTextual() ? sha.asText() : null;
    }
    return null;
  }

  @Nullable
  private static String coreMetadataSha256Of(final String attrs) {
    String fromCore = fragmentHashes(attribute(attrs, "data-core-metadata")).get("sha256");
    return fromCore != null ? fromCore : fragmentHashes(attribute(attrs, "data-dist-info-metadata")).get("sha256");
  }

  private static String unescape(final @Nullable String value) {
    if (value == null) {
      return null;
    }
    return value.replace("&gt;", ">").replace("&lt;", "<").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&amp;", "&");
  }

  private static String escape(final String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }
}
