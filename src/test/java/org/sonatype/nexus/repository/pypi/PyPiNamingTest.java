/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.pypi;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Against real distribution file names: wheels (PEP 427) and source distributions (PEP 376/517).
 */
public class PyPiNamingTest
{
  @Test
  public void versionsOfWheels() {
    assertEquals("1.16.0", PyPiNaming.releaseVersion("six-1.16.0-py2.py3-none-any.whl"));
    assertEquals("2.34.2", PyPiNaming.releaseVersion("requests-2.34.2-py3-none-any.whl"));
    // a project name with a dash: the version is still four fields from the right
    assertEquals("5.0.0", PyPiNaming.releaseVersion("zope-interface-5.0.0-py3-none-any.whl"));
    // a platform tag with underscores
    assertEquals("1.26.0",
        PyPiNaming.releaseVersion("numpy-1.26.0-cp312-cp312-manylinux_2_17_x86_64.manylinux2014_x86_64.whl"));
    // pre-release and local versions
    assertEquals("2.0.0rc1", PyPiNaming.releaseVersion("foo-2.0.0rc1-py3-none-any.whl"));
    assertEquals("1.0+local.1", PyPiNaming.releaseVersion("foo-1.0+local.1-py3-none-any.whl"));
  }

  @Test
  public void versionsOfSourceDistributions() {
    assertEquals("1.16.0", PyPiNaming.releaseVersion("six-1.16.0.tar.gz"));
    assertEquals("5.0.0", PyPiNaming.releaseVersion("zope.interface-5.0.0.tar.gz"));
    assertEquals("5.0.0", PyPiNaming.releaseVersion("zope-interface-5.0.0.tar.gz"));
    // an underscore-separated source distribution
    assertEquals("0.1", PyPiNaming.releaseVersion("some_pkg-0.1.tar.gz"));
    assertEquals("2.34.2", PyPiNaming.releaseVersion("requests-2.34.2.tar.bz2"));
    assertEquals("2.34.2", PyPiNaming.releaseVersion("requests-2.34.2.zip"));
  }

  @Test
  public void degenerateNamesFallBackToZero() {
    assertEquals("0", PyPiNaming.releaseVersion("notaversion.whl"));
    assertEquals("0", PyPiNaming.releaseVersion("noversion.tar.gz"));
    assertEquals("0", PyPiNaming.releaseVersion("-1.0.whl"));
  }
}
