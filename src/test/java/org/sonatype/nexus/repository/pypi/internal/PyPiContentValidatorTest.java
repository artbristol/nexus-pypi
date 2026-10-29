/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.pypi.internal;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.sonatype.nexus.mime.internal.DefaultMimeSupport;
import org.sonatype.nexus.repository.mime.DefaultContentValidator;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PyPiContentValidatorTest
{
  private final PyPiContentValidator underTest =
      new PyPiContentValidator(new DefaultContentValidator(new DefaultMimeSupport()));

  @Test
  public void typesProxyProducedContentExplicitly() throws Exception {
    assertEquals("text/html; charset=utf-8",
        determine("/.pypi/six/@html", "<html></html>".getBytes(StandardCharsets.UTF_8)));
    assertEquals("application/vnd.pypi.simple.v1+json",
        determine("/.pypi/six/@json", "{\"files\":[]}".getBytes(StandardCharsets.UTF_8)));
    assertEquals("application/json",
        determine("/.pypi/six/files", "[{\"filename\":\"a\"}]".getBytes(StandardCharsets.UTF_8)));
    assertEquals("text/plain; charset=utf-8",
        determine("/simple/six/six-1.16.0.tar.gz.metadata",
            "Metadata-Version: 2.1\nName: six\n".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void typesDistributionFilesAsOpaqueArchives() throws Exception {
    // a wheel is a zip, an sdist a gzip; neither is demanded a type, as the file is verified by its hash
    byte[] zip = new byte[]{0x50, 0x4b, 0x03, 0x04, 0, 0, 0, 0};
    byte[] gzip = new byte[]{(byte) 0x1f, (byte) 0x8b, 0x08, 0, 0, 0, 0, 0};
    assertEquals("application/octet-stream", determine("/simple/six/six-1.16.0-py2.py3-none-any.whl", zip));
    assertEquals("application/octet-stream", determine("/simple/six/six-1.16.0.tar.gz", gzip));
    assertEquals("application/octet-stream", determine("/simple/six/extra-1.0.tar.gz", "whatever".getBytes()));
  }

  @Test
  public void delegatesOtherPathsToTheDefaultValidator() throws Exception {
    assertEquals("application/json", determine("/other/config.json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
  }

  private String determine(final String name, final byte[] content) throws Exception {
    return underTest.determineContentType(true, () -> new ByteArrayInputStream(content), null, name, null);
  }
}
