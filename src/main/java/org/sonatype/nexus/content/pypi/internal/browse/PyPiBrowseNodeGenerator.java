/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.pypi.internal.browse;

import org.sonatype.nexus.repository.content.browse.AssetPathBrowseNodeGenerator;
import org.sonatype.nexus.repository.pypi.internal.PyPiFormat;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Browse tree follows asset paths, e.g. {@code simple/requests/requests-2.34.2-py3-none-any.whl}.
 */
@Component
@Qualifier(PyPiFormat.NAME)
public class PyPiBrowseNodeGenerator
    extends AssetPathBrowseNodeGenerator
{
}
