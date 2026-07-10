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
   * @param zip the zip archive contents to extract
   * @param targetDir the directory to extract into
   * @return {@code targetDir}
   */
  public static Path extractToTemp(InputStream zip, Path targetDir) throws IOException {
    Path normalizedTarget = targetDir.normalize();
    Files.createDirectories(normalizedTarget);
    try (ZipInputStream zis = new ZipInputStream(zip)) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        Path resolved = normalizedTarget.resolve(entry.getName()).normalize();
        if (!resolved.startsWith(normalizedTarget)) {
          throw new IOException("Zip entry escapes target directory (zip-slip): " + entry.getName());
        }
        Path parent = resolved.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.copy(zis, resolved);
        zis.closeEntry();
      }
    }
    return targetDir;
  }
}
