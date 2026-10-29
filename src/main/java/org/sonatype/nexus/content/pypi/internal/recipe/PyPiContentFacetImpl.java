/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.pypi.internal.recipe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import javax.annotation.Nullable;

import org.sonatype.nexus.content.pypi.PyPiContentFacet;
import org.sonatype.nexus.repository.InvalidContentException;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.repository.pypi.PyPiNaming;
import org.sonatype.nexus.repository.pypi.PyPiPaths.FileRef;
import org.sonatype.nexus.repository.pypi.internal.PyPiFormat;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;

import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA256;

/**
 * A {@link PyPiContentFacet} that persists to a {@link org.sonatype.nexus.repository.content.facet.ContentFacet}.
 *
 * One component per project release: namespace = empty, name = project, version = the release version of the file,
 * kind = pypi. Each distribution file is an asset of that component. Index pages, file tables and core metadata are
 * component-less assets under {@code /.pypi/} (index and table) or alongside the file (metadata).
 */
@Component
@Qualifier(PyPiFormat.NAME)
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class PyPiContentFacetImpl
    extends ContentFacetSupport
    implements PyPiContentFacet
{
  private static final String KIND = "pypi";

  @Autowired
  public PyPiContentFacetImpl(@Qualifier(PyPiFormat.NAME) final FormatStoreManager formatStoreManager) {
    super(formatStoreManager);
  }

  @Override
  public Optional<Content> get(final String path) throws IOException {
    return assets().path(path).find().map(FluentAsset::download);
  }

  @Override
  public Content putFile(
      final FileRef file,
      final Payload content,
      @Nullable final String expectedSha256) throws IOException
  {
    try (TempBlob blob = blobs().ingest(content, HASHING)) {
      checkHash(file.assetPath(), blob, expectedSha256);
      return saveFile(file, blob).markAsCached(content).download();
    }
  }

  private FluentAsset saveFile(final FileRef file, final TempBlob blob) {
    FluentComponent component = components()
        .name(file.project())
        .namespace("")
        .version(PyPiNaming.releaseVersion(file.filename()))
        .kind(KIND)
        .getOrCreate();
    return assets()
        .path(file.assetPath())
        .kind(FILE_KIND)
        .component(component)
        .blob(blob)
        .save();
  }

  @Override
  public Content putFile(
      final FileRef file,
      final Path path,
      @Nullable final String contentType,
      final CacheInfo cacheInfo,
      @Nullable final String etag,
      @Nullable final DateTime lastModified) throws IOException
  {
    try (TempBlob blob = blobs().ingest(path, contentType, HASHING, false)) {
      // the bytes are verified by the streaming download that produced them; this records the cache details and the
      // upstream content headers with the asset, as storing from the upstream payload does
      Content cached = new Content(new BytesPayload(new byte[0], contentType));
      cached.getAttributes().set(CacheInfo.class, cacheInfo);
      if (etag != null) {
        cached.getAttributes().set(Content.CONTENT_ETAG, etag);
      }
      if (lastModified != null) {
        cached.getAttributes().set(Content.CONTENT_LAST_MODIFIED, lastModified);
      }
      return saveFile(file, blob).markAsCached(cached).download();
    }
  }

  @Override
  public Content putMetadata(
      final FileRef file,
      final Payload content,
      @Nullable final String expectedSha256) throws IOException
  {
    try (TempBlob blob = blobs().ingest(content, HASHING)) {
      checkHash(file.metadataAssetPath(), blob, expectedSha256);
      return assets()
          .path(file.metadataAssetPath())
          .kind(METADATA_KIND)
          .blob(blob)
          .save()
          .markAsCached(content)
          .download();
    }
  }

  @Override
  public Content putAsset(final String path, final String kind, final Payload content) throws IOException {
    try (TempBlob blob = blobs().ingest(content, HASHING)) {
      return assets()
          .path(path)
          .kind(kind)
          .blob(blob)
          .save()
          .markAsCached(content)
          .download();
    }
  }

  private static void checkHash(final String path, final TempBlob blob, @Nullable final String expectedSha256)
      throws InvalidContentException {
    if (expectedSha256 == null) {
      return;
    }
    String actual = blob.getHashes().get(SHA256) == null ? null : String.valueOf(blob.getHashes().get(SHA256));
    if (actual == null || !actual.equalsIgnoreCase(expectedSha256)) {
      throw new InvalidContentException(
          "Content hash of " + path + " is " + actual + ", the index declared " + expectedSha256);
    }
  }
}
