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
import org.sonatype.nexus.repository.rest.api.model.ReplicationAttributes;
import org.sonatype.nexus.repository.rest.api.model.SimpleApiProxyRepository;
import org.sonatype.nexus.repository.rest.api.model.StorageAttributes;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A PyPI proxy repository, as the REST API returns it.
 */
public class PyPiProxyApiRepository
    extends SimpleApiProxyRepository
{
  private final PyPiAttributes pypi;

  @SuppressWarnings("squid:S00107") // suppress constructor parameter count
  public PyPiProxyApiRepository(
      final String name,
      final String url,
      final Boolean online,
      final StorageAttributes storage,
      final CleanupPolicyAttributes cleanup,
      final ProxyAttributes proxy,
      final NegativeCacheAttributes negativeCache,
      final HttpClientAttributes httpClient,
      final String routingRuleName,
      final ReplicationAttributes replication,
      final PyPiAttributes pypi)
  {
    super(name, PyPiFormat.NAME, url, online, storage, cleanup, proxy, negativeCache, httpClient, routingRuleName,
        replication);
    this.pypi = pypi;
  }

  @JsonProperty("pypi")
  public PyPiAttributes getPypi() {
    return pypi;
  }
}
