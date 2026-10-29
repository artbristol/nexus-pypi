/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.pypi.rest;

import org.sonatype.nexus.content.pypi.internal.recipe.PyPiProxyFacet;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.pypi.PyPiPaths;
import org.sonatype.nexus.repository.rest.api.ProxyRepositoryApiRequestToConfigurationConverter;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PyPiProxyRepositoryApiRequestToConfigurationConverter
    extends ProxyRepositoryApiRequestToConfigurationConverter<PyPiProxyRepositoryApiRequest>
{
  @Autowired
  public PyPiProxyRepositoryApiRequestToConfigurationConverter(final RoutingRuleStore routingRuleStore) {
    super(routingRuleStore);
  }

  @Override
  public Configuration convert(final PyPiProxyRepositoryApiRequest request) {
    Configuration configuration = super.convert(request);
    PyPiAttributes pypi = request.getPypi();
    String indexPath = pypi != null && pypi.getIndexPath() != null ? pypi.getIndexPath() : PyPiPaths.DEFAULT_INDEX_PATH;
    configuration.attributes(PyPiProxyFacet.CONFIG_KEY).set("indexPath", indexPath);
    return configuration;
  }
}
