/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.pypi.internal;

import org.sonatype.nexus.repository.httpclient.AutoBlockConfiguration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import static org.sonatype.nexus.repository.http.HttpStatus.PROXY_AUTHENTICATION_REQUIRED;

/**
 * PyPI's auto-block policy. The default policy blocks the remote on a 401, but pypi.org never demands authentication
 * (a missing project is a 404), so a 401 can only come from a private index whose credentials are missing or wrong -
 * a configuration problem that should surface as request failures, not take the whole proxy down until someone clears
 * the block. As with the default policy, 407 and 5xx still block the remote.
 */
@Component
@Qualifier(PyPiFormat.NAME)
public class PyPiAutoBlockConfiguration
    implements AutoBlockConfiguration
{
  @Override
  public boolean shouldBlock(final int statusCode) {
    return statusCode == PROXY_AUTHENTICATION_REQUIRED || statusCode >= 500;
  }
}
