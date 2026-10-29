/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.pypi.internal.browse;

import java.util.List;

import org.sonatype.nexus.repository.browse.node.BrowsePath;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.pypi.PyPiPaths;

import org.junit.Test;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PyPiBrowseNodeGeneratorTest
{
  private final PyPiBrowseNodeGenerator generator = new PyPiBrowseNodeGenerator();

  /**
   * The proxy's own state (rewritten index pages, parsed file tables) must not become browse nodes; the paths are the
   * real ones the proxy stores, as built by {@link PyPiPaths}.
   */
  @Test
  public void metaAssetsAreNotInTheBrowseTree() {
    List<String> metaPaths = List.of(
        PyPiPaths.indexAssetPath("requests", false),
        PyPiPaths.indexAssetPath("requests", true),
        PyPiPaths.fileTableAssetPath("requests"));
    for (String path : metaPaths) {
      Asset asset = asset(path);
      assertTrue(path, generator.computeAssetPaths(asset).isEmpty());
      assertTrue(path, generator.computeComponentPaths(asset).isEmpty());
    }
  }

  /**
   * The client-visible tree still follows the asset path, one node per segment, e.g.
   * {@code simple/requests/requests-2.34.2-py3-none-any.whl}.
   */
  @Test
  public void clientVisibleAssetsFollowTheirAssetPaths() {
    Asset asset = asset(new PyPiPaths.FileRef("requests", "requests-2.34.2-py3-none-any.whl").assetPath());
    assertEquals(
        List.of("/simple/", "/simple/requests/", "/simple/requests/requests-2.34.2-py3-none-any.whl"),
        requestPaths(generator.computeAssetPaths(asset)));
    assertEquals(3, generator.computeComponentPaths(asset).size());
  }

  private static List<String> requestPaths(final List<BrowsePath> paths) {
    return paths.stream().map(BrowsePath::getRequestPath).collect(toList());
  }

  private static Asset asset(final String path) {
    Asset asset = mock(Asset.class);
    when(asset.path()).thenReturn(path);
    return asset;
  }
}
