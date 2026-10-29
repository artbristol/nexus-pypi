/*
 * PyPI proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.pypi.internal.recipe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.repository.InvalidContentException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests of what a {@link StreamingDownload} releases to its readers, and when: everything but the most recent chunk
 * while the download runs, the tail only once the body matches what the index declared, and a failure to every reader
 * instead of a complete but corrupt file.
 */
public class StreamingDownloadTest
{
  /**
   * The hold-back granularity, as StreamingDownload uses it.
   */
  private static final int CHUNK_SIZE = 64 * 1024;

  private static final String PATH = "/simple/six/six-1.16.0-py2.py3-none-any.whl";

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private Thread downloader;

  @Test
  public void readerReceivesTheExactBytesOfTheUpstream() throws Exception {
    byte[] content = content(2);
    StreamingDownload underTest = download(sha256(content), (long) content.length);
    runDownload(underTest, upstream(content));
    join(downloader);

    assertTrue(underTest.isComplete());
    underTest.awaitReadable();
    assertArrayEquals(content, readAll(underTest));
  }

  @Test
  public void bytesFlowToTheReaderWhileTheDownloadIsStillRunning() throws Exception {
    byte[] content = content(3);
    CountDownLatch gate = new CountDownLatch(1);
    StreamingDownload underTest = download(sha256(content), (long) content.length);
    AtomicReference<Exception> failure = runDownload(underTest, paused(content, 2, gate));

    InputStream reader = underTest.openReader();
    try {
      byte[] chunk = new byte[CHUNK_SIZE];
      assertEquals(CHUNK_SIZE, reader.readNBytes(chunk, 0, CHUNK_SIZE));
      assertArrayEquals(firstChunk(content), chunk);
      assertFalse("the download is still running", underTest.isComplete());
    }
    finally {
      reader.close();
    }

    gate.countDown();
    join(downloader);
    assertNull(failure.get());
    assertTrue(underTest.isComplete());

    InputStream lateReader = underTest.openReader();
    try {
      assertArrayEquals(content, lateReader.readAllBytes());
      assertEquals(-1, lateReader.read());
    }
    finally {
      lateReader.close();
    }
  }

  @Test
  public void theCurrentChunkIsHeldBackUntilTheNextOneArrives() throws Exception {
    byte[] content = content(2);
    CountDownLatch gate = new CountDownLatch(1);
    StreamingDownload underTest = download(sha256(content), (long) content.length);
    AtomicReference<Exception> failure = runDownload(underTest, paused(content, 1, gate));

    InputStream reader = underTest.openReader();
    AtomicReference<Integer> firstByte = new AtomicReference<>();
    Thread blockedReader = blockUntilReadable(reader, firstByte);
    CountDownLatch awaited = new CountDownLatch(1);
    Thread blockedAwaiter = new Thread(() -> {
      try {
        underTest.awaitReadable();
      }
      catch (IOException e) {
        // checked below by the latch not counting down
      }
      finally {
        awaited.countDown();
      }
    });
    blockedAwaiter.start();

    blockedReader.join(300);
    assertTrue("nothing is released before the next chunk arrives", blockedReader.isAlive());
    assertFalse("a response is not served before the first chunk is released", awaited.await(300, TimeUnit.MILLISECONDS));

    gate.countDown();
    join(downloader);
    join(blockedReader);
    join(blockedAwaiter);
    assertEquals(content[0] & 0xff, firstByte.get().intValue());
    assertTrue(underTest.isComplete());
    assertNull(failure.get());
  }

  @Test
  public void aReaderNeverSeesACompleteButCorruptFile() throws Exception {
    byte[] content = content(3);
    byte[] corrupt = content.clone();
    corrupt[corrupt.length - 1] ^= 1; // the tail does not match the declared hash
    CountDownLatch gate = new CountDownLatch(1);
    StreamingDownload underTest = download(sha256(content), (long) content.length);
    AtomicReference<Exception> failure = runDownload(underTest, paused(corrupt, 2, gate));

    InputStream reader = underTest.openReader();
    try {
      byte[] chunk = new byte[CHUNK_SIZE];
      assertEquals(CHUNK_SIZE, reader.readNBytes(chunk, 0, CHUNK_SIZE));
      assertArrayEquals(firstChunk(content), chunk);

      gate.countDown();
      join(downloader);
      assertSame(InvalidContentException.class, failure.get().getClass());

      // the tail is only released once the body has been verified, so the reader runs out of released bytes and
      // fails instead of receiving the complete but corrupt file
      byte[] received = new byte[2 * CHUNK_SIZE];
      int receivedN = 0;
      try {
        while (receivedN < received.length) {
          int n = reader.read(received, receivedN, received.length - receivedN);
          if (n < 0) {
            throw new AssertionError("the reader was told the corrupt file was complete");
          }
          receivedN += n;
        }
        reader.read(); // the tail was never released: this must fail
        throw new AssertionError("the reader was served the corrupt tail");
      }
      catch (IOException e) {
        assertSame(InvalidContentException.class, e.getCause().getClass());
      }
      assertFalse(underTest.isComplete());
    }
    finally {
      reader.close();
    }

    try {
      underTest.awaitReadable();
      throw new AssertionError("the download failed");
    }
    catch (InvalidContentException e) {
      // answered 404, and not negatively cached
      assertTrue(e.getMessage().contains(PATH));
    }
  }

  @Test
  public void aBodyShorterThanTheDeclaredSizeIsRejected() throws Exception {
    byte[] content = content(1);
    StreamingDownload underTest = download(sha256(content), (long) content.length * 2);

    AtomicReference<Exception> failure = runDownload(underTest, upstream(content));
    join(downloader);
    assertSame(InvalidContentException.class, failure.get().getClass());
    assertFalse(underTest.isComplete());
    assertNull("there is nothing to serve from a failed download", underTest.openReader());

    try {
      underTest.awaitReadable();
      throw new AssertionError("the download failed");
    }
    catch (InvalidContentException e) {
      assertTrue(e.getMessage().contains("Received " + content.length + " of " + content.length * 2));
    }
  }

  @Test
  public void aBodyTheIndexDeclaresNothingAboutIsAccepted() throws Exception {
    byte[] content = content(2);
    StreamingDownload underTest = download(null, null);

    AtomicReference<Exception> failure = runDownload(underTest, upstream(content));
    join(downloader);
    assertNull(failure.get());
    assertTrue(underTest.isComplete());
    assertArrayEquals(content, readAll(underTest));
  }

  @Test
  public void aShortBodyIsAcceptedWhenTheIndexDeclaresNothingAboutIt() throws Exception {
    // no hash and no size to check the body against: it is accepted as it arrives, as it always has been
    byte[] content = content(2);
    StreamingDownload underTest = download(null, null);

    AtomicReference<Exception> failure = runDownload(underTest, upstream(content, content.length / 2));
    join(downloader);
    assertNull(failure.get());
    assertTrue(underTest.isComplete());
    assertArrayEquals(Arrays.copyOf(content, content.length / 2), readAll(underTest));
  }

  // helpers

  private StreamingDownload download(final String sha256, final Long size) throws IOException {
    // the facet hands StreamingDownload a file that exists, as Files.createTempFile makes one
    Path file = temporaryFolder.getRoot().toPath().resolve("download-" + System.nanoTime() + ".part");
    Files.createFile(file);
    return new StreamingDownload(file, PATH, sha256, size, null, null);
  }

  private AtomicReference<Exception> runDownload(final StreamingDownload download, final InputStream upstream) {
    AtomicReference<Exception> failure = new AtomicReference<>();
    downloader = new Thread(() -> {
      try {
        download.download(upstream);
      }
      catch (Exception e) {
        failure.set(e);
      }
    });
    downloader.start();
    return failure;
  }

  /**
   * A thread that reads one byte, blocking for as long as the download releases nothing.
   */
  private static Thread blockUntilReadable(final InputStream reader, final AtomicReference<Integer> firstByte) {
    Thread thread = new Thread(() -> {
      try {
        firstByte.set(reader.read());
      }
      catch (IOException e) {
        // the assertion on the reader fails instead
      }
    });
    thread.start();
    return thread;
  }

  private static void join(final Thread thread) throws InterruptedException {
    thread.join(TimeUnit.MINUTES.toMillis(1));
    assertFalse("the thread is still running", thread.isAlive());
  }

  private static byte[] readAll(final StreamingDownload download) throws IOException {
    InputStream reader = download.openReader();
    try {
      return reader.readAllBytes();
    }
    finally {
      reader.close();
    }
  }

  /**
   * Serves the bytes in one go.
   */
  private static InputStream upstream(final byte[] content) {
    return upstream(content, content.length);
  }

  /**
   * Serves the first {@code length} bytes of the content, then ends the body.
   */
  private static InputStream upstream(final byte[] content, final int length) {
    return new InputStream()
    {
      private int position;

      @Override
      public int read() {
        return position < length ? content[position++] & 0xff : -1;
      }
    };
  }

  /**
   * Serves the content in chunks of {@link #CHUNK_SIZE}, blocking before serving the chunk after {@code pauseAfter}
   * chunks, so that the test can observe the download half-way.
   */
  private static InputStream paused(final byte[] content, final int pauseAfter, final CountDownLatch gate) {
    return new InputStream()
    {
      private int position;

      private final byte[] one = new byte[1];

      @Override
      public int read() throws IOException {
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
      }

      @Override
      public int read(final byte[] b, final int off, final int len) throws IOException {
        if (position >= content.length) {
          return -1;
        }
        if (position / CHUNK_SIZE == pauseAfter && position % CHUNK_SIZE == 0) {
          try {
            if (!gate.await(1, TimeUnit.MINUTES)) {
              throw new IOException("test gate timed out");
            }
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
          }
        }
        int n = Math.min(len, content.length - position);
        System.arraycopy(content, position, b, off, n);
        position += n;
        return n;
      }
    };
  }

  /**
   * {@code chunks} chunks of {@link #CHUNK_SIZE}, each filled with its index.
   */
  private static byte[] content(final int chunks) {
    byte[] content = new byte[chunks * CHUNK_SIZE];
    for (int chunk = 0; chunk < chunks; chunk++) {
      Arrays.fill(content, chunk * CHUNK_SIZE, (chunk + 1) * CHUNK_SIZE, (byte) chunk);
    }
    return content;
  }

  private static byte[] firstChunk(final byte[] content) {
    return Arrays.copyOf(content, CHUNK_SIZE);
  }

  private static String sha256(final byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
    catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
