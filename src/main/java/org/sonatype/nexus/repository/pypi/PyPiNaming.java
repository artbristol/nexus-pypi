/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.pypi;

/**
 * Best-effort extraction of a distribution file's release version from its file name, for grouping a project's files
 * into components (PEP 427 wheels and PEP 376/517 source distributions).
 *
 * The version only affects which component a file is grouped under - never whether or how the file is served, which
 * is addressed by its path and verified by its hash - so the rare file names that are ambiguous (wheels with a build
 * tag) are grouped under a slightly wrong version rather than failing.
 */
public final class PyPiNaming
{
  private PyPiNaming() {
  }

  public static String releaseVersion(final String filename) {
    if (filename.endsWith(".whl")) {
      String base = filename.substring(0, filename.length() - ".whl".length());
      // {name}-{version}(-{build})?-{python}-{abi}-{platform}: the version is four fields from the right
      String[] fields = base.split("-");
      if (fields.length < 5) {
        return "0";
      }
      String version = fields[fields.length - 4];
      return version.isEmpty() ? "0" : version;
    }
    String base = stripArchiveExtension(filename);
    int i = Math.max(base.lastIndexOf('-'), base.lastIndexOf('_'));
    if (i <= 0) {
      return "0";
    }
    String version = base.substring(i + 1);
    return version.isEmpty() ? "0" : version;
  }

  static String stripArchiveExtension(final String filename) {
    for (String extension : new String[]{".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tgz", ".whl", ".zip"}) {
      if (filename.endsWith(extension)) {
        return filename.substring(0, filename.length() - extension.length());
      }
    }
    return filename;
  }
}
