/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.pypi.internal;

import org.sonatype.nexus.repository.Format;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * PyPI repository format.
 */
@Component
@Qualifier(PyPiFormat.NAME)
public class PyPiFormat
    extends Format
{
  public static final String NAME = "pypi";

  public PyPiFormat() {
    super(NAME);
  }
}
