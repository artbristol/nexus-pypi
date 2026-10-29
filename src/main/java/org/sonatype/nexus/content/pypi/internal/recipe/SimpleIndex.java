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
import java.util.Locale;
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
  private static final Pattern FRAGMENT_HASH = Pattern.compile("([A-Za-z0-9]+)=([0-9a-fA-F]+)");

  private SimpleIndex() {
  }

  /**
   * The result of parsing and rewriting an index page.
   *
   * @param rewritten       the index page with its file links pointing at the repository
   * @param files           the files the index describes
   * @param unparsedAnchors the number of HTML {@code <a>} tags so malformed that no href could be read from them; they
   *                        are left untouched in {@code rewritten} (always 0 for JSON)
   */
  public record Parsed(String rewritten, List<PyPiFileEntry> files, int unparsedAnchors) {
  }

  /**
   * One attribute of an HTML tag: its lower-cased name, its value (null when the attribute is boolean and has none) and
   * its span in the tag's source, so that everything but a rewritten href is copied out verbatim.
   */
  private record Attribute(String name, String value, int start, int end) {
  }

  /**
   * Parses and rewrites a PEP 503 HTML index page.
   *
   * Nexus Core ships no HTML parser, so the anchors are read with a small quote-aware scanner: it finds
   * {@code <a ...>...</a>} tags, reads their attributes - values in double or single quotes, unquoted values, boolean
   * attributes, and {@code >} inside a quoted value - and rewrites each file link's href. An anchor too malformed to
   * yield an href is left untouched and counted in the result's {@code unparsedAnchors}.
   *
   * @param baseUrl the base URL the index page was fetched from, used to resolve relative file links
   * @param html    the index page
   */
  public static Parsed parseHtml(final String baseUrl, final String html) {
    requireNonNull(html);
    URI base = URI.create(baseUrl);
    List<PyPiFileEntry> files = new ArrayList<>();
    StringBuilder out = new StringBuilder(html.length());
    int unparsedAnchors = 0;
    int i = 0;
    while (i < html.length()) {
      int anchorAt = nextAnchor(html, i);
      if (anchorAt < 0) {
        out.append(html, i, html.length());
        break;
      }
      out.append(html, i, anchorAt);
      int tagEnd = openingTagEnd(html, anchorAt + 2);
      if (tagEnd < 0) {
        // the tag is never closed: leave "<a" untouched and keep scanning after it
        out.append(html, anchorAt, anchorAt + 2);
        unparsedAnchors++;
        i = anchorAt + 2;
        continue;
      }
      int closeEnd = closingTagEnd(html, tagEnd + 1);
      if (closeEnd < 0) {
        // the anchor is never closed: leave the opening tag untouched and keep scanning after it
        out.append(html, anchorAt, tagEnd + 1);
        unparsedAnchors++;
        i = tagEnd + 1;
        continue;
      }
      int closeStart = html.lastIndexOf('<', closeEnd - 1);
      String tag = html.substring(anchorAt + 2, tagEnd);
      List<Attribute> attrs = parseAttributes(tag);
      String href = attribute(attrs, "href");
      if (href == null) {
        out.append(html, anchorAt, closeEnd);
        unparsedAnchors++;
        i = closeEnd;
        continue;
      }
      int fragmentAt = href.indexOf('#');
      String urlPart = fragmentAt < 0 ? href : href.substring(0, fragmentAt);
      String fragment = fragmentAt < 0 ? null : href.substring(fragmentAt + 1);
      String inner = html.substring(tagEnd + 1, closeStart);
      // PEP 503: the anchor text is the file name; if it carries markup, fall back to the URL's file name
      String text = inner.indexOf('<') < 0 ? inner : "";
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
      out.append(html, anchorAt, anchorAt + 2);
      appendTagWithHref(out, tag, attrs, newHref);
      out.append('>').append(inner).append(html, closeStart, closeEnd);
      i = closeEnd;
    }
    return new Parsed(out.toString(), files, unparsedAnchors);
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
    return new Parsed(mapper.writeValueAsString(root), entries, 0);
  }

  // helpers

  private static String resolve(final URI base, final String url) {
    if (url.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
      return url;
    }
    return base.resolve(url).toString();
  }

  /**
   * The start of the next {@code <a>} opening tag at or after {@code from}, or -1. The tag name is case-insensitive and
   * must be followed by whitespace, {@code >} or {@code /}, so that e.g. {@code <abbr>} is not taken for an anchor.
   */
  private static int nextAnchor(final String html, final int from) {
    for (int i = from; i < html.length() - 1; i++) {
      if (html.charAt(i) != '<') {
        continue;
      }
      char c = html.charAt(i + 1);
      if (c != 'a' && c != 'A') {
        continue;
      }
      if (i + 2 >= html.length()) {
        return i;
      }
      char after = html.charAt(i + 2);
      if (after == '>' || after == '/' || Character.isWhitespace(after)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * The index of the {@code >} that ends the opening tag, ignoring a {@code >} inside a quoted attribute value, or -1.
   */
  private static int openingTagEnd(final String html, final int from) {
    char quote = 0;
    for (int i = from; i < html.length(); i++) {
      char c = html.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        }
      }
      else if (c == '"' || c == '\'') {
        quote = c;
      }
      else if (c == '>') {
        return i;
      }
    }
    return -1;
  }

  /**
   * The index just after the {@code >} of the first {@code </a>} end tag at or after {@code from}, or -1.
   */
  private static int closingTagEnd(final String html, final int from) {
    for (int i = from; i + 2 < html.length(); i++) {
      if (html.charAt(i) == '<' && html.charAt(i + 1) == '/'
          && (html.charAt(i + 2) == 'a' || html.charAt(i + 2) == 'A')) {
        int j = i + 3;
        while (j < html.length() && Character.isWhitespace(html.charAt(j))) {
          j++;
        }
        if (j < html.length() && html.charAt(j) == '>') {
          return j + 1;
        }
      }
    }
    return -1;
  }

  /**
   * The opening tag's attributes, in source order: values in double or single quotes, unquoted values, and boolean
   * attributes, which have no value.
   */
  private static List<Attribute> parseAttributes(final String tag) {
    List<Attribute> attrs = new ArrayList<>();
    int i = 0;
    int n = tag.length();
    while (i < n) {
      while (i < n && (Character.isWhitespace(tag.charAt(i)) || tag.charAt(i) == '/')) {
        i++;
      }
      if (i >= n) {
        break;
      }
      int nameStart = i;
      while (i < n && !Character.isWhitespace(tag.charAt(i)) && tag.charAt(i) != '=' && tag.charAt(i) != '>') {
        i++;
      }
      String name = tag.substring(nameStart, i).toLowerCase(Locale.ROOT);
      if (name.isEmpty()) {
        i++;
        continue;
      }
      String value = null;
      int end = i;
      int j = i;
      while (j < n && Character.isWhitespace(tag.charAt(j))) {
        j++;
      }
      if (j < n && tag.charAt(j) == '=') {
        j++;
        while (j < n && Character.isWhitespace(tag.charAt(j))) {
          j++;
        }
        if (j < n && (tag.charAt(j) == '"' || tag.charAt(j) == '\'')) {
          char quote = tag.charAt(j);
          int close = tag.indexOf(quote, j + 1);
          if (close < 0) {
            // the value is never closed: it runs to the end of the tag
            value = tag.substring(j + 1);
            end = n;
            i = n;
          }
          else {
            value = tag.substring(j + 1, close);
            end = close + 1;
            i = close + 1;
          }
        }
        else {
          int valueStart = j;
          while (j < n && !Character.isWhitespace(tag.charAt(j)) && tag.charAt(j) != '>') {
            j++;
          }
          if (j > valueStart) {
            value = tag.substring(valueStart, j);
            end = j;
          }
          i = j;
        }
      }
      attrs.add(new Attribute(name, value, nameStart, end));
    }
    return attrs;
  }

  /**
   * The value of the named attribute, or null when the tag does not give it one (a boolean attribute has none).
   */
  @Nullable
  private static String attribute(final List<Attribute> attrs, final String name) {
    for (Attribute attr : attrs) {
      if (attr.name().equals(name)) {
        return attr.value();
      }
    }
    return null;
  }

  /**
   * Appends the opening tag's source with each href's value replaced by the escaped new one in double quotes;
   * everything else, including the whitespace around the attributes, is copied out verbatim.
   */
  private static void appendTagWithHref(final StringBuilder out, final String tag, final List<Attribute> attrs,
      final String newHref) {
    int pos = 0;
    for (Attribute attr : attrs) {
      out.append(tag, pos, attr.start());
      if (attr.name().equals("href")) {
        out.append("href=\"").append(escape(newHref)).append('"');
      }
      else {
        out.append(tag, attr.start(), attr.end());
      }
      pos = attr.end();
    }
    out.append(tag, pos, tag.length());
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
  private static String coreMetadataSha256Of(final List<Attribute> attrs) {
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
