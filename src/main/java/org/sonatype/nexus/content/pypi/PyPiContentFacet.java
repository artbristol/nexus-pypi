/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.pypi;

import java.io.IOException;
import java.util.Optional;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.pypi.PyPiPaths.FileRef;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;

import com.google.common.collect.ImmutableList;

import static org.sonatype.nexus.common.hash.HashAlgorithm.MD5;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA1;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA256;

/**
 * Provides persistent content for the 'pypi' format.
 */
@Facet.Exposed
public interface PyPiContentFacet
    extends ContentFacet
{
  Iterable<HashAlgorithm> HASHING = ImmutableList.of(MD5, SHA1, SHA256);

  /**
   * Asset kind of distribution files.
   */
  String FILE_KIND = "file";

  /**
   * Asset kind of a project's rewritten index page.
   */
  String INDEX_KIND = "index";

  /**
   * Asset kind of a project's parsed file table.
   */
  String FILE_TABLE_KIND = "file-table";

  /**
   * Asset kind of a file's core metadata (PEP 658/714).
   */
  String METADATA_KIND = "metadata";

  Optional<Content> get(String path) throws IOException;

  /**
   * Stores a distribution file as an asset of the component for its project and version, after checking it against the
   * hash the index declared for it, when there is one.
   *
   * @param file the file
   * @param expectedSha256 the hash the index declared for the file, or null if the index declared none
   * @throws org.sonatype.nexus.repository.InvalidContentException if the content does not match the declared hash
   */
  Content putFile(FileRef file, Payload content, @Nullable String expectedSha256) throws IOException;

  /**
   * Stores a file's core metadata (PEP 658/714) after checking it against the hash the index declared for it, when there
   * is one.
   *
   * @param expectedSha256 the hash the index declared for the metadata, or null if the index declared none
   * @throws org.sonatype.nexus.repository.InvalidContentException if the content does not match the declared hash
   */
  Content putMetadata(FileRef file, Payload content, @Nullable String expectedSha256) throws IOException;

  /**
   * Stores a component-less asset: a rewritten index page, or a file table.
   */
  Content putAsset(String path, String kind, Payload content) throws IOException;
}
