package org.catalogueoflife.editor.coldp.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Pure-JDK helper to zip a folder of ColDP files into an archive and to extract an uploaded
 * ColDP zip archive into a temp folder, safely.
 *
 * <p>ColDP archives are flat: all data/metadata files sit directly at the archive root, so {@link
 * #zipFolder(Path, Path)} only looks at the immediate children of {@code folder} (non-recursive).
 */
public final class ColdpZip {

  // Zip-bomb guard for extractToTemp(InputStream, Path, long): an archive with more than this many
  // (non-directory) entries is rejected outright, independent of the byte cap -- a flat ColDP
  // archive has, at most, a couple dozen data/metadata files, so this is generous headroom, not a
  // realistic limit for a genuine archive.
  private static final int MAX_ENTRIES = 10_000;

  private ColdpZip() {}

  /**
   * Zips every regular file directly under {@code folder} (flat, non-recursive) into {@code
   * targetZip}, each zip entry named by the file's filename.
   *
   * @param folder the folder whose top-level regular files are zipped
   * @param targetZip the zip file to create/overwrite
   */
  public static void zipFolder(Path folder, Path targetZip) throws IOException {
    try (OutputStream fos = Files.newOutputStream(targetZip);
        ZipOutputStream zos = new ZipOutputStream(fos)) {
      try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
        for (Path file : files) {
          if (!Files.isRegularFile(file)) {
            continue;
          }
          ZipEntry entry = new ZipEntry(file.getFileName().toString());
          zos.putNextEntry(entry);
          Files.copy(file, zos);
          zos.closeEntry();
        }
      }
    }
  }

  /**
   * Extracts every entry of {@code zip} into {@code targetDir}, creating parent directories as
   * needed. Directory entries are skipped. Guards against zip-slip: any entry whose resolved path
   * escapes {@code targetDir} causes an {@link IOException} to be thrown and no further entries
   * are extracted.
   *
   * <p>Unbounded (no total-size or entry-count cap): safe only for archives this application
   * produced itself (e.g. re-reading a just-written export for a round-trip test) -- never for an
   * uploaded, untrusted archive. See {@link #extractToTemp(InputStream, Path, long)} for that case.
   *
   * @param zip the zip archive contents to extract
   * @param targetDir the directory to extract into
   * @return {@code targetDir}
   */
  public static Path extractToTemp(InputStream zip, Path targetDir) throws IOException {
    return extractToTemp(zip, targetDir, Long.MAX_VALUE);
  }

  /**
   * Extracts every entry of {@code zip} into {@code targetDir}, same as {@link
   * #extractToTemp(InputStream, Path)}, but additionally guards against zip bombs: an
   * {@link IOException} is thrown -- and extraction aborted immediately, mid-entry, without
   * finishing the write of whatever oversized entry tripped it -- once either the running total of
   * decompressed bytes across all entries exceeds {@code maxTotalBytes}, or the archive holds more
   * than {@value #MAX_ENTRIES} (non-directory) entries. The byte count is enforced against actual
   * bytes read off the (already-inflating) {@link ZipInputStream} as they're copied, not against
   * the entry's self-reported {@link ZipEntry#getSize()} -- a crafted/streamed entry cannot lie its
   * way past the cap by declaring a false size.
   *
   * @param zip the zip archive contents to extract; untrusted (e.g. user-uploaded)
   * @param targetDir the directory to extract into
   * @param maxTotalBytes the cap on summed decompressed bytes across all entries
   * @return {@code targetDir}
   */
  public static Path extractToTemp(InputStream zip, Path targetDir, long maxTotalBytes) throws IOException {
    Path normalizedTarget = targetDir.normalize();
    Files.createDirectories(normalizedTarget);
    int entryCount = 0;
    long totalBytes = 0;
    try (ZipInputStream zis = new ZipInputStream(zip)) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        if (++entryCount > MAX_ENTRIES) {
          throw new IOException("archive exceeds " + MAX_ENTRIES + " entries");
        }
        Path resolved = normalizedTarget.resolve(entry.getName()).normalize();
        if (!resolved.startsWith(normalizedTarget)) {
          throw new IOException("Zip entry escapes target directory (zip-slip): " + entry.getName());
        }
        Path parent = resolved.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        totalBytes = copyCapped(zis, resolved, totalBytes, maxTotalBytes);
        zis.closeEntry();
      }
    }
    return targetDir;
  }

  // Streams one zip entry to disk in fixed-size chunks, checking the running cross-entry total
  // after every chunk (not just once at the end) -- so a single, individually-huge entry aborts
  // partway through instead of first being fully inflated to disk. Returns the new running total.
  private static long copyCapped(InputStream in, Path target, long totalSoFar, long maxTotalBytes)
      throws IOException {
    byte[] buf = new byte[8192];
    long total = totalSoFar;
    try (OutputStream out = Files.newOutputStream(target)) {
      int n;
      while ((n = in.read(buf)) != -1) {
        total += n;
        if (total > maxTotalBytes) {
          throw new IOException("archive exceeds " + maxTotalBytes + " bytes (decompressed)");
        }
        out.write(buf, 0, n);
      }
    }
    return total;
  }
}
