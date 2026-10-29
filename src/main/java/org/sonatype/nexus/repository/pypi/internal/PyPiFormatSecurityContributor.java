/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.pypi.internal;

import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.security.RepositoryFormatSecurityContributor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * PyPI format security contributor.
 */
@Component
public class PyPiFormatSecurityContributor
    extends RepositoryFormatSecurityContributor
{
  @Autowired
  public PyPiFormatSecurityContributor(@Qualifier(PyPiFormat.NAME) final Format format) {
    super(format);
  }
}
