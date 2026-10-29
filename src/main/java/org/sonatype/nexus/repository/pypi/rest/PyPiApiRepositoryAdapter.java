/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.pypi.rest;

import org.sonatype.nexus.content.pypi.internal.recipe.PyPiProxyFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.pypi.PyPiPaths;
import org.sonatype.nexus.repository.pypi.internal.PyPiFormat;
import org.sonatype.nexus.repository.rest.api.SimpleApiRepositoryAdapter;
import org.sonatype.nexus.repository.rest.api.model.AbstractApiRepository;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;
import org.sonatype.nexus.repository.types.ProxyType;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Adds the {@code pypi} section to PyPI proxy repositories returned by the REST API, which the repository forms of the
 * UI read.
 */
@Component
@Qualifier(PyPiFormat.NAME)
public class PyPiApiRepositoryAdapter
    extends SimpleApiRepositoryAdapter
{
  @Autowired
  public PyPiApiRepositoryAdapter(final RoutingRuleStore routingRuleStore) {
    super(routingRuleStore);
  }

  @Override
  public AbstractApiRepository adapt(final Repository repository) {
    if (!ProxyType.NAME.equals(repository.getType().toString())) {
      return super.adapt(repository);
    }
    String indexPath = repository.getConfiguration().attributes(PyPiProxyFacet.CONFIG_KEY)
        .get("indexPath", String.class, PyPiPaths.DEFAULT_INDEX_PATH);
    return new PyPiProxyApiRepository(
        repository.getName(),
        repository.getUrl(),
        repository.getConfiguration().isOnline(),
        getStorageAttributes(repository),
        getCleanupPolicyAttributes(repository),
        getProxyAttributes(repository),
        getNegativeCacheAttributes(repository),
        getHttpClientAttributes(repository),
        getRoutingRuleName(repository),
        getReplicationAttributes(repository),
        new PyPiAttributes(indexPath));
  }
}
