/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.pypi.internal.recipe;

import java.util.List;

import org.sonatype.nexus.repository.pypi.PyPiFileEntry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Against the shape of pypi.org's Simple API output: PEP 503 HTML (with PEP 658/714 data attributes) and PEP 691 JSON
 * (with PEP 700 size and upload-time).
 */
public class SimpleIndexTest
{
  private static final String BASE = "https://pypi.org/simple/six/";

  private static final String HTML = String.join("\n",
      "<!DOCTYPE html>",
      "<html lang=\"en\">",
      "  <head>",
      "    <meta name=\"pypi:repository-version\" content=\"1.4\">",
      "    <title>Links for six</title>",
      "  </head>",
      "  <body>",
      "    <h1>Links for six</h1>",
      "<a href=\"https://files.pythonhosted.org/packages/ba/bb/dfa0141a32d773c47e4dede1a617c59a23b74dd302e449cf85413fc96bc4/six-1.16.0-py2.py3-none-any.whl#sha256=8abb2f1d86890a27b58d66095a12d102acafa81495c3a074c7e862466c84b62f\" data-requires-python=\"&gt;=2.7, !=3.0.*, !=3.1.*, !=3.2.*\">six-1.16.0-py2.py3-none-any.whl</a><br />",
      "<a href=\"https://files.pythonhosted.org/packages/e4/93/916bb3876d1d95d29a4fcc0ed13d9b529f7accff049222858b6db0c9612a/six-1.16.0.tar.gz#sha256=1e61c37477a1626458e36f7b1d82aa5c9b094fa4802892072e49de9c60c4c926\" data-requires-python=\"&gt;=2.7, !=3.0.*, !=3.1.*, !=3.2.*\" data-core-metadata=\"sha256=8c384ba3e979480faae2859d3c5e6c1276dd2c3616e322e124d52c8cfc556f27\" data-dist-info-metadata=\"sha256=8c384ba3e979480faae2859d3c5e6c1276dd2c3616e322e124d52c8cfc556f27\">six-1.16.0.tar.gz</a><br />",
      "<a href=\"packages/extra-1.0.tar.gz#sha256=abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789\">extra-1.0.tar.gz</a><br />",
      "  </body>",
      "</html>");

  private static final String JSON = "{"
      + "\"meta\":{\"api-version\":\"1\",\"last-serial\":12345678},"
      + "\"files\":["
      + "{\"core-metadata\":false,\"data-dist-info-metadata\":false,"
      + "\"filename\":\"six-1.16.0-py2.py3-none-any.whl\","
      + "\"hashes\":{\"sha256\":\"8abb2f1d86890a27b58d66095a12d102acafa81495c3a074c7e862466c84b62f\"},"
      + "\"provenance\":null,\"requires-python\":\">=2.7, !=3.0.*, !=3.1.*, !=3.2.*\","
      + "\"size\":11084,\"upload-time\":\"2021-06-26T13:22:25.257105Z\","
      + "\"url\":\"https://files.pythonhosted.org/packages/ba/bb/dfa0141a32d773c47e4dede1a617c59a23b74dd302e449cf85413fc96bc4/six-1.16.0-py2.py3-none-any.whl\","
      + "\"yanked\":false},"
       + "{\"core-metadata\":{\"sha256\":\"8c384ba3e979480faae2859d3c5e6c1276dd2c3616e322e124d52c8cfc556f27\"},"
       + "\"data-dist-info-metadata\":{\"sha256\":\"8c384ba3e979480faae2859d3c5e6c1276dd2c3616e322e124d52c8cfc556f27\"},"
       + "\"filename\":\"six-1.16.0.tar.gz\","
      + "\"hashes\":{\"sha256\":\"1e61c37477a1626458e36f7b1d82aa5c9b094fa4802892072e49de9c60c4c926\"},"
      + "\"provenance\":null,\"requires-python\":\">=2.7, !=3.0.*, !=3.1.*, !=3.2.*\","
      + "\"size\":34220,\"upload-time\":\"2021-06-26T13:22:27.139426Z\","
      + "\"url\":\"https://files.pythonhosted.org/packages/e4/93/916bb3876d1d95d29a4fcc0ed13d9b529f7accff049222858b6db0c9612a/six-1.16.0.tar.gz\","
      + "\"yanked\":false}"
      + "]}";

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  public void rewritesHtmlFileLinksToTheBareFileName() throws Exception {
    SimpleIndex.Parsed parsed = SimpleIndex.parseHtml(BASE, HTML);
    String rewritten = parsed.rewritten();
    // the file links are relative to the project page, with the hash fragment kept for the client to verify
    assertTrue(rewritten, rewritten.contains(
        "<a href=\"six-1.16.0-py2.py3-none-any.whl#sha256=8abb2f1d86890a27b58d66095a12d102acafa81495c3a074c7e862466c84b62f\""));
    assertTrue(rewritten, rewritten.contains("href=\"six-1.16.0.tar.gz#sha256=1e61c37477a1626458e36f7b1d82aa5c9b094fa4802892072e49de9c60c4c926\""));
    assertTrue(rewritten, rewritten.contains("href=\"extra-1.0.tar.gz#sha256=abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789\""));
    // the file host no longer appears in any link
    assertFalse(rewritten, rewritten.contains("files.pythonhosted.org"));
    // data attributes are passed through unchanged
    assertTrue(rewritten, rewritten.contains("data-requires-python=\"&gt;=2.7, !=3.0.*, !=3.1.*, !=3.2.*\""));
    assertTrue(rewritten, rewritten.contains("data-core-metadata=\"sha256=8c384ba3e979480faae2859d3c5e6c1276dd2c3616e322e124d52c8cfc556f27\""));
    // the rest of the page is intact
    assertTrue(rewritten, rewritten.contains("<h1>Links for six</h1>"));
  }

  @Test
  public void parsesTheHtmlFileTable() {
    SimpleIndex.Parsed parsed = SimpleIndex.parseHtml(BASE, HTML);
    List<PyPiFileEntry> files = parsed.files();
    assertEquals(3, files.size());

    PyPiFileEntry wheel = files.get(0);
    assertEquals("six-1.16.0-py2.py3-none-any.whl", wheel.filename());
    assertEquals("https://files.pythonhosted.org/packages/ba/bb/dfa0141a32d773c47e4dede1a617c59a23b74dd302e449cf85413fc96bc4/six-1.16.0-py2.py3-none-any.whl",
        wheel.url());
    assertEquals("8abb2f1d86890a27b58d66095a12d102acafa81495c3a074c7e862466c84b62f", wheel.sha256());
    // the HTML entity in data-requires-python is unescaped
    assertEquals(">=2.7, !=3.0.*, !=3.1.*, !=3.2.*", wheel.requiresPython());
    assertFalse(wheel.coreMetadata());
    assertFalse(wheel.yanked());

    PyPiFileEntry sdist = files.get(1);
    assertEquals("six-1.16.0.tar.gz", sdist.filename());
    // PEP 714: data-core-metadata marks a file whose core metadata is served separately
    assertTrue(sdist.coreMetadata());
    assertEquals("1e61c37477a1626458e36f7b1d82aa5c9b094fa4802892072e49de9c60c4c926", sdist.sha256());
    assertEquals("8c384ba3e979480faae2859d3c5e6c1276dd2c3616e322e124d52c8cfc556f27", sdist.coreMetadataSha256());

    // a relative file link is resolved against the index page
    PyPiFileEntry relative = files.get(2);
    assertEquals("extra-1.0.tar.gz", relative.filename());
    assertEquals("https://pypi.org/simple/six/packages/extra-1.0.tar.gz", relative.url());
    assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", relative.sha256());
  }

  @Test
  public void rewritesJsonFileLinksAndParsesTheTable() throws Exception {
    JsonNode index = mapper.readTree(JSON);
    SimpleIndex.Parsed parsed = SimpleIndex.parseJson(mapper, BASE, index);
    JsonNode rewritten = mapper.readTree(parsed.rewritten());
    assertEquals("six-1.16.0-py2.py3-none-any.whl", rewritten.get("files").get(0).get("url").asText());
    assertEquals("six-1.16.0.tar.gz", rewritten.get("files").get(1).get("url").asText());
    // the hashes are kept, so the client can still verify
    assertEquals("8abb2f1d86890a27b58d66095a12d102acafa81495c3a074c7e862466c84b62f",
        rewritten.get("files").get(0).get("hashes").get("sha256").asText());
    // the PEP 700 fields are untouched
    assertEquals(11084, rewritten.get("files").get(0).get("size").asLong());
    assertEquals("2021-06-26T13:22:25.257105Z", rewritten.get("files").get(0).get("upload-time").asText());

    List<PyPiFileEntry> files = parsed.files();
    assertEquals(2, files.size());
    assertEquals("https://files.pythonhosted.org/packages/ba/bb/dfa0141a32d773c47e4dede1a617c59a23b74dd302e449cf85413fc96bc4/six-1.16.0-py2.py3-none-any.whl",
        files.get(0).url());
    assertFalse(files.get(0).coreMetadata());
    assertEquals(Long.valueOf(34220), files.get(1).size());
    assertEquals("2021-06-26T13:22:27.139426Z", files.get(1).uploadTime());
    assertTrue(files.get(1).coreMetadata());
    // PEP 714: the object form carries the core metadata's own hash
    assertEquals("8c384ba3e979480faae2859d3c5e6c1276dd2c3616e322e124d52c8cfc556f27", files.get(1).coreMetadataSha256());
    assertNull(files.get(0).coreMetadataSha256());
  }

  @Test
  public void yankedFlag() throws Exception {
    String yankedJson = "{\"files\":["
        + "{\"filename\":\"a-1.0.tar.gz\",\"hashes\":{\"sha256\":\"abc\"},\"url\":\"https://f.example/a-1.0.tar.gz\","
        + "\"yanked\":\"deprecated in favour of 2.0\"},"
        + "{\"filename\":\"a-2.0.tar.gz\",\"hashes\":{\"sha256\":\"def\"},\"url\":\"https://f.example/a-2.0.tar.gz\","
        + "\"yanked\":false}]}";
    SimpleIndex.Parsed parsed = SimpleIndex.parseJson(mapper, BASE, mapper.readTree(yankedJson));
    assertTrue(parsed.files().get(0).yanked());
    assertEquals("deprecated in favour of 2.0", parsed.files().get(0).yankReason());
    assertFalse(parsed.files().get(1).yanked());
  }
}
