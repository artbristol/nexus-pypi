/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.pypi.rest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The {@code pypi} section of a PyPI proxy repository, as the repository forms of the UI send it.
 */
public class PyPiAttributes
{
  @Schema(description = "Path of the Simple API under the remote URL: /simple (the default) for pypi.org, or empty "
      + "for an index served at the root of the remote URL", example = "/simple")
  private final String indexPath;

  @JsonCreator
  public PyPiAttributes(@JsonProperty("indexPath") final String indexPath) {
    this.indexPath = indexPath;
  }

  public String getIndexPath() {
    return indexPath;
  }
}
