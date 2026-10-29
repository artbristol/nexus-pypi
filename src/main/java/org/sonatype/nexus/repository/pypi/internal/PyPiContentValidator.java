/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.pypi.internal;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.sonatype.nexus.common.io.InputStreamSupplier;
import org.sonatype.nexus.mime.MimeRulesSource;
import org.sonatype.nexus.repository.mime.ContentValidator;
import org.sonatype.nexus.repository.mime.DefaultContentValidator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;
import static org.sonatype.nexus.repository.view.ContentTypes.APPLICATION_OCTET_STREAM;

/**
 * PyPI content validator.
 *
 * Distribution files are checked against the hash the index declares for them before they are stored, which is what
 * strict content type validation approximates by comparing type and name; and a file is whatever its author published
 * (a wheel is a zip, an sdist a tarball, some an opaque archive), so none of them has a single content type to demand.
 * The index pages, the file table and the core metadata are typed explicitly, as the proxy produces them.
 */
@Component
@Qualifier(PyPiFormat.NAME)
public class PyPiContentValidator
    implements ContentValidator
{
  private static final String SIMPLE_PREFIX = "/simple/";

  public static final String INDEX_HTML = "text/html; charset=utf-8";

  public static final String INDEX_JSON = "application/vnd.pypi.simple.v1+json";

  public static final String JSON = "application/json";

  public static final String METADATA = "text/plain; charset=utf-8";

  private final DefaultContentValidator defaultContentValidator;

  @Autowired
  public PyPiContentValidator(final DefaultContentValidator defaultContentValidator) {
    this.defaultContentValidator = requireNonNull(defaultContentValidator);
  }

  @Nonnull
  @Override
  public String determineContentType(
      final boolean strictContentTypeValidation,
      final InputStreamSupplier contentSupplier,
      @Nullable final MimeRulesSource mimeRulesSource,
      @Nullable final String contentName,
      @Nullable final String declaredContentType) throws IOException
  {
    if (contentName != null) {
      String explicit = explicitType(contentName);
      if (explicit != null) {
        return explicit;
      }
      if (contentName.startsWith(SIMPLE_PREFIX)) {
        return APPLICATION_OCTET_STREAM;
      }
    }
    return defaultContentValidator.determineContentType(
        strictContentTypeValidation, contentSupplier, mimeRulesSource, contentName, declaredContentType);
  }

  @Nullable
  private static String explicitType(final String name) {
    if (name.endsWith("@html")) {
      return INDEX_HTML;
    }
    if (name.endsWith("@json")) {
      return INDEX_JSON;
    }
    if (name.endsWith("/files")) {
      return JSON;
    }
    if (name.endsWith(".metadata")) {
      return METADATA;
    }
    return null;
  }
}
