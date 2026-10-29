/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.pypi;

import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import static java.util.Objects.requireNonNull;

/**
 * One distribution file of a project, as a PyPI Simple API index describes it (PEP 503, and PEP 658/691/700/714
 * where applicable).
 *
 * This is both the unit of the cached file table and what the proxy verifies a downloaded file against: {@code url}
 * is where the file is fetched from, and {@code hashes} (at least a {@code sha256}) is what the downloaded bytes must
 * match before they are stored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PyPiFileEntry(
    @JsonProperty("filename") String filename,
    @JsonProperty("url") String url,
    @JsonProperty("hashes") Map<String, String> hashes,
    @JsonProperty("requires-python") @Nullable String requiresPython,
    @JsonProperty("yanked") boolean yanked,
    @JsonProperty("yank-reason") @Nullable String yankReason,
    @JsonProperty("size") @Nullable Long size,
    @JsonProperty("upload-time") @Nullable String uploadTime,
    @JsonProperty("core-metadata") boolean coreMetadata,
    @JsonProperty("core-metadata-sha256") @Nullable String coreMetadataSha256,
    @JsonProperty("provenance") @Nullable String provenance)
{
  public PyPiFileEntry {
    requireNonNull(filename);
    requireNonNull(url);
    if (hashes == null) {
      hashes = Map.of();
    } else {
      hashes = Map.copyOf(hashes);
    }
  }

  @JsonIgnore
  @Nullable
  public String sha256() {
    return hashes.get("sha256");
  }
}
