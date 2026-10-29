/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.pypi.internal.store;

import org.sonatype.nexus.content.pypi.internal.browse.PyPiBrowseNodeDAO;
import org.sonatype.nexus.repository.content.store.FormatStoreSupport;
import org.sonatype.nexus.repository.pypi.internal.PyPiFormat;

import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Configures the content store bindings for the PyPI format.
 */
@Qualifier(PyPiFormat.NAME)
public class PyPiStoresProvider
    extends FormatStoreSupport<PyPiContentRepositoryDAO, PyPiComponentDAO, PyPiAssetDAO, PyPiAssetBlobDAO, PyPiBrowseNodeDAO>
{
  // nothing to add...
}
