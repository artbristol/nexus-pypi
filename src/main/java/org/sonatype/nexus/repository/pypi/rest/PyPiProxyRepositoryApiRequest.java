/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.pypi.rest;

import org.sonatype.nexus.repository.pypi.internal.PyPiFormat;
import org.sonatype.nexus.repository.rest.api.model.CleanupPolicyAttributes;
import org.sonatype.nexus.repository.rest.api.model.HttpClientAttributes;
import org.sonatype.nexus.repository.rest.api.model.NegativeCacheAttributes;
import org.sonatype.nexus.repository.rest.api.model.ProxyAttributes;
import org.sonatype.nexus.repository.rest.api.model.ProxyRepositoryApiRequest;
import org.sonatype.nexus.repository.rest.api.model.ReplicationAttributes;
import org.sonatype.nexus.repository.rest.api.model.StorageAttributes;
import org.sonatype.nexus.swagger.EditionVisibility;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request to create or update a PyPI proxy repository.
 *
 * The Simple API root - the value of pip's {@code --index-url} - is the remote URL plus the {@code indexPath} of the
 * {@code pypi} section, which the repository forms of the UI send too: e.g. {@code https://pypi.org} and
 * {@code /simple}, the default when there is no {@code pypi} section.
 */
@JsonFilter(EditionVisibility.NAME)
@JsonIgnoreProperties({"format", "type"})
public class PyPiProxyRepositoryApiRequest
    extends ProxyRepositoryApiRequest
{
  private final PyPiAttributes pypi;

  @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
  @SuppressWarnings("squid:S00107") // suppress constructor parameter count
  public PyPiProxyRepositoryApiRequest(
      @JsonProperty("name") final String name,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final StorageAttributes storage,
      @JsonProperty("cleanup") final CleanupPolicyAttributes cleanup,
      @JsonProperty("proxy") final ProxyAttributes proxy,
      @JsonProperty("negativeCache") final NegativeCacheAttributes negativeCache,
      @JsonProperty("httpClient") final HttpClientAttributes httpClient,
      @JsonProperty("routingRuleName") @JsonAlias("routingRule") final String routingRule,
      @JsonProperty("replication") @JsonInclude(value = Include.NON_EMPTY,
          content = Include.NON_NULL) final ReplicationAttributes replication,
      @JsonProperty("pypi") final PyPiAttributes pypi)
  {
    super(name, PyPiFormat.NAME, online, storage, cleanup, proxy, negativeCache, httpClient, routingRule, replication);
    this.pypi = pypi;
  }

  public PyPiAttributes getPypi() {
    return pypi;
  }
}
