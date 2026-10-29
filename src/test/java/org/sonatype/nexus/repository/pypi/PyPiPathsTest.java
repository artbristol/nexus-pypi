/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.pypi;

import org.sonatype.nexus.repository.pypi.PyPiPaths.FileRef;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PyPiPathsTest
{
  @Test
  public void normalizesProjectNames() {
    assertEquals("requests", PyPiPaths.normalizeProject("requests"));
    assertEquals("requests", PyPiPaths.normalizeProject("Requests"));
    assertEquals("zope-interface", PyPiPaths.normalizeProject("zope.interface"));
    assertEquals("my-package", PyPiPaths.normalizeProject("My.Package"));
    assertEquals("a-b", PyPiPaths.normalizeProject("a__b"));
    assertEquals("a-b-c-d", PyPiPaths.normalizeProject("a.b-c_d"));
    assertEquals("urllib3", PyPiPaths.normalizeProject("urllib3"));
  }

  @Test
  public void parsesIndexPaths() {
    assertTrue(PyPiPaths.isIndex("/simple/requests/"));
    assertTrue(PyPiPaths.isIndex("/simple/requests"));
    assertEquals("requests", PyPiPaths.parseIndex("/simple/requests/").orElseThrow());
    // the project is normalised, so Requests and requests share one cache entry
    assertEquals("requests", PyPiPaths.parseIndex("/simple/Requests").orElseThrow());
    assertEquals("zope-interface", PyPiPaths.parseIndex("/simple/zope.interface/").orElseThrow());

    assertFalse(PyPiPaths.isIndex("/simple/"));
    assertFalse(PyPiPaths.isIndex("/simple/requests/six-1.16.0.tar.gz"));
    assertFalse(PyPiPaths.parseIndex("/").isPresent());
    assertFalse(PyPiPaths.parseIndex("/simple/requests/file.whl").isPresent());
  }

  @Test
  public void parsesFilePaths() {
    FileRef file = PyPiPaths.parseFile("/simple/requests/six-1.16.0-py2.py3-none-any.whl").orElseThrow();
    assertEquals(new FileRef("requests", "six-1.16.0-py2.py3-none-any.whl"), file);
    assertEquals("/simple/requests/six-1.16.0-py2.py3-none-any.whl", file.assetPath());
    assertEquals("/simple/requests/six-1.16.0-py2.py3-none-any.whl.metadata", file.metadataAssetPath());

    FileRef sdist = PyPiPaths.parseFile("/simple/requests/requests-2.34.2.tar.gz").orElseThrow();
    assertEquals("requests-2.34.2.tar.gz", sdist.filename());
  }

  @Test
  public void fileNamesMayContainDotsAndDashes() {
    for (String filename : new String[]{
        "six-1.16.0-py2.py3-none-any.whl",
        "numpy-1.26.0-cp312-cp312-manylinux_2_17_x86_64.manylinux2014_x86_64.whl",
        "zope.interface-5.0.0.tar.gz",
        "PIL-9.0.0-1-cp311-cp311-win_amd64.whl",
        "package.with.dots-0.1-py3-none-any.whl"}) {
      FileRef ref = PyPiPaths.parseFile("/simple/proj/" + filename).orElseThrow(() -> new AssertionError(filename));
      assertEquals(filename, ref.filename());
      assertEquals("proj", ref.project());
    }
  }

  @Test
  public void metadataPathsAreNotFilePaths() {
    // a .metadata request is core metadata (PEP 658/714), not the file itself
    assertFalse(PyPiPaths.parseFile("/simple/requests/six-1.16.0.tar.gz.metadata").isPresent());
    FileRef ref = PyPiPaths.parseMetadata("/simple/requests/six-1.16.0.tar.gz.metadata").orElseThrow();
    assertEquals(new FileRef("requests", "six-1.16.0.tar.gz"), ref);
    assertFalse(PyPiPaths.parseMetadata("/simple/requests/six-1.16.0.tar.gz").isPresent());
    assertFalse(PyPiPaths.parseMetadata("/simple/requests/").isPresent());
  }

  @Test
  public void assetPaths() {
    assertEquals("/.pypi/requests/@html", PyPiPaths.indexAssetPath("requests", false));
    assertEquals("/.pypi/requests/@json", PyPiPaths.indexAssetPath("requests", true));
    assertEquals("/.pypi/requests/files", PyPiPaths.fileTableAssetPath("requests"));
  }

  /**
   * The remote URL is the Simple API root (pip's --index-url); a project's page is its path plus /{project}/.
   */
  @Test
  public void indexUpstreamPaths() {
    assertEquals("/simple/requests/", PyPiPaths.indexUpstreamPath("/simple", "requests"));
    assertEquals("/simple/requests/", PyPiPaths.indexUpstreamPath("/simple/", "requests"));
    assertEquals("/requests/", PyPiPaths.indexUpstreamPath("/", "requests"));
    assertEquals("/requests/", PyPiPaths.indexUpstreamPath("", "requests"));
    assertEquals("/pypi/requests/", PyPiPaths.indexUpstreamPath("/pypi", "requests"));
  }
}
