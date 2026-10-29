/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.pypi.internal.recipe;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.annotation.Nullable;

import org.sonatype.nexus.repository.InvalidContentException;

import com.google.common.annotations.VisibleForTesting;
import org.joda.time.DateTime;

import static java.nio.file.StandardOpenOption.READ;
import static java.util.Objects.requireNonNull;

/**
 * An upstream download of a distribution file, written to a temporary file that any number of clients read while it
 * grows.
 *
 * Bytes are only released to readers once they are known to be good: the most recently written chunk is held back
 * until the next one arrives, and the last one until the whole body has been checked against the size and the sha256
 * the index declared for the file and the file has been stored. A reader therefore never sees a complete but corrupt
 * file, and one that reads to the end finds the file in the repository, so the request that follows its download does
 * not need the upstream again. If the body ends early or does not match what the index declared, the download fails
 * and every reader fails with it, and nothing is stored.
 * When the index declares no hash and no size there is nothing to check the body against, and it is accepted as it
 * arrives.
 */
final class StreamingDownload
{
  /**
   * Also the granularity of the hold-back: readers get bytes once the next chunk has arrived.
   */
  private static final int CHUNK_SIZE = 64 * 1024;

  private static final long READER_WAIT_MILLIS = 1000;

  private final Path file;

  /**
   * Path of the asset the file is stored as, for messages.
   */
  private final String path;

  /**
   * The sha256 the index declared for the file, or null if it declared none.
   */
  @Nullable
  private final String sha256;

  /**
   * The size the index declared for the file, or null if it declared none.
   */
  @Nullable
  private final Long size;

  /**
   * The upstream ETag of the file, or null if it sent none; recorded with the stored asset, and sent to the clients
   * the file streams to, as the regular proxy path does.
   */
  @Nullable
  private final String etag;

  /**
   * The upstream Last-Modified of the file, or null if it sent none.
   */
  @Nullable
  private final DateTime lastModified;

  private final Object lock = new Object();

  // guarded by lock
  private long released;

  /**
   * The size of the body, set once it has been fully received and verified; released by {@link #finish()}.
   */
  private long total;

  private boolean complete;

  private Exception failure;

  private boolean deleted;

  StreamingDownload(
      final Path file,
      final String path,
      @Nullable final String sha256,
      @Nullable final Long size,
      @Nullable final String etag,
      @Nullable final DateTime lastModified)
  {
    this.file = requireNonNull(file);
    this.path = requireNonNull(path);
    this.sha256 = sha256;
    this.size = size;
    this.etag = etag;
    this.lastModified = lastModified;
  }

  Path file() {
    return file;
  }

  @Nullable
  String etag() {
    return etag;
  }

  @Nullable
  DateTime lastModified() {
    return lastModified;
  }

  /**
   * Copies the upstream body into the file, releasing bytes to readers as they are verified, except for the tail,
   * which {@link #finish()} releases. A transport failure or a body that does not match what the index declared fails
   * the download for good; readers fail too.
   *
   * @throws IOException if the download failed; readers fail with the same failure
   */
  void download(final InputStream upstream) throws IOException {
    try {
      MessageDigest digest = digest();
      long written = 0;
      try (OutputStream out = Files.newOutputStream(file)) {
        byte[] buffer = new byte[CHUNK_SIZE];
        while (true) {
          int n = upstream.readNBytes(buffer, 0, buffer.length);
          if (n <= 0) {
            if (size != null && written < size) {
              throw new InvalidContentException(
                  "Received " + written + " of " + size + " bytes of " + path + ", the index declared " + size);
            }
            break;
          }
          out.write(buffer, 0, n);
          out.flush();
          if (digest != null) {
            digest.update(buffer, 0, n);
          }
          release(written); // everything but this chunk
          written += n;
        }
      }
      finally {
        closeQuietly(upstream);
      }
      verify(written, digest);
      total = written;
    }
    catch (IOException | RuntimeException e) {
      fail(e);
      throw e;
    }
  }

  /**
   * Releases the tail and completes the download, once the file has been stored: a client that reads to the end must
   * find the file in the repository, or the request that follows its download would fetch it from upstream again. The
   * bytes are verified by then; a failure to store them only means the file is not cached, so the clients still
   * receive the complete file.
   */
  void finish() {
    synchronized (lock) {
      released = total;
      complete = true;
      lock.notifyAll();
    }
  }

  private static void closeQuietly(final InputStream in) {
    try {
      in.close();
    }
    catch (IOException | RuntimeException e) {
      // nothing more to do with it
    }
  }

  /**
   * Records the failure that every reader fails with, unless the download already completed.
   */
  void fail(final Exception e) {
    synchronized (lock) {
      if (!complete) {
        failure = e;
        lock.notifyAll();
      }
    }
  }

  private void release(final long upTo) {
    synchronized (lock) {
      released = upTo;
      lock.notifyAll();
    }
  }

  /**
   * The digest to check the body against, or null if the index declared no hash.
   */
  @Nullable
  private MessageDigest digest() {
    if (sha256 == null) {
      return null;
    }
    try {
      return MessageDigest.getInstance("SHA-256");
    }
    catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void verify(final long written, @Nullable final MessageDigest digest) {
    if (size != null && written != size) {
      throw new InvalidContentException(
          "Received " + written + " bytes of " + path + ", the index declared " + size);
    }
    if (digest != null) {
      String actual = HexFormat.of().formatHex(digest.digest());
      if (!actual.equalsIgnoreCase(sha256)) {
        throw new InvalidContentException(
            "Content hash of " + path + " is " + actual + ", the index declared " + sha256);
      }
    }
  }

  /**
   * Waits until the first byte has been released to readers, the download has completed, or it has failed.
   *
   * A response is only served from a download that has reached that point: a failure before then is thrown to the
   * caller, which answers it with a status (404 for content that does not match the index, 502 for a failed
   * transfer) instead of a broken transfer.
   *
   * @throws IOException if the download failed before releasing anything, or the wait was interrupted
   */
  void awaitReadable() throws IOException {
    synchronized (lock) {
      while (released <= 0 && !complete && failure == null) {
        try {
          lock.wait(READER_WAIT_MILLIS);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while waiting for upstream", e);
        }
      }
      if (failure != null) {
        if (failure instanceof IOException io) {
          throw io;
        }
        if (failure instanceof RuntimeException runtime) {
          throw runtime;
        }
        throw new IOException("Upstream download failed", failure);
      }
    }
  }

  /**
   * Opens a reader from the start of the file, or returns {@code null} once the file is gone (the content is then in
   * the repository, or the download failed).
   */
  @Nullable
  InputStream openReader() throws IOException {
    synchronized (lock) {
      if (deleted || failure != null) {
        return null;
      }
      // opened under the lock so the file cannot be deleted in between; an open file survives deletion
      return new TailingInputStream(FileChannel.open(file, READ));
    }
  }

  /**
   * Deletes the file; readers that have it open carry on.
   */
  void delete() throws IOException {
    synchronized (lock) {
      deleted = true;
      Files.deleteIfExists(file);
    }
  }

  @VisibleForTesting
  boolean isComplete() {
    synchronized (lock) {
      return complete;
    }
  }

  private final class TailingInputStream
      extends InputStream
  {
    private final FileChannel channel;

    private long position;

    TailingInputStream(final FileChannel channel) {
      this.channel = channel;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
      if (len == 0) {
        return 0;
      }
      long available;
      synchronized (lock) {
        while (released <= position && !complete && failure == null) {
          try {
            lock.wait(READER_WAIT_MILLIS);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for upstream", e);
          }
        }
        if (failure != null) {
          throw new IOException("Upstream download failed", failure);
        }
        available = released - position;
      }
      if (available <= 0) {
        return -1;
      }
      int n = channel.read(ByteBuffer.wrap(b, off, (int) Math.min(len, available)), position);
      if (n > 0) {
        position += n;
      }
      return n;
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }
}
