package org.catalogueoflife.editor.coldp.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ColdpZipTest {

  @Test
  void roundTripPreservesFileContent(@TempDir Path tmp) throws IOException {
    Path src = tmp.resolve("src");
    Files.createDirectories(src);
    Path dest = tmp.resolve("dest");
    Path zip = tmp.resolve("archive.zip");

    byte[] nameUsageContent = "ID\tscientificName\n1\tAbies alba\n".getBytes(StandardCharsets.UTF_8);
    byte[] metadataContent = "title: Test Checklist\n".getBytes(StandardCharsets.UTF_8);
    Files.write(src.resolve("NameUsage.tsv"), nameUsageContent);
    Files.write(src.resolve("metadata.yaml"), metadataContent);

    ColdpZip.zipFolder(src, zip);

    Path returned;
    try (var in = Files.newInputStream(zip)) {
      returned = ColdpZip.extractToTemp(in, dest);
    }

    assertThat(returned).isEqualTo(dest);
    assertThat(dest.resolve("NameUsage.tsv")).exists();
    assertThat(dest.resolve("metadata.yaml")).exists();
    assertThat(Files.readAllBytes(dest.resolve("NameUsage.tsv"))).isEqualTo(nameUsageContent);
    assertThat(Files.readAllBytes(dest.resolve("metadata.yaml"))).isEqualTo(metadataContent);
  }

  @Test
  void zipSlipEntryIsRejected(@TempDir Path tmp) throws IOException {
    Path dest = tmp.resolve("dest");
    Path zip = tmp.resolve("evil.zip");

    try (var zos = new ZipOutputStream(Files.newOutputStream(zip))) {
      zos.putNextEntry(new ZipEntry("../evil.txt"));
      zos.write("pwned".getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
    }

    try (var in = Files.newInputStream(zip)) {
      assertThatThrownBy(() -> ColdpZip.extractToTemp(in, dest)).isInstanceOf(IOException.class);
    }

    // the sibling path escaping dest must not have been written
    Path escaped = tmp.resolve("evil.txt");
    assertThat(escaped).doesNotExist();
  }

  @Test
  void archiveExceedingByteCapIsRejected(@TempDir Path tmp) throws IOException {
    Path src = tmp.resolve("src");
    Files.createDirectories(src);
    Path dest = tmp.resolve("dest");
    Path zip = tmp.resolve("big.zip");

    // A single entry well over the tiny cap below -- proves the running decompressed-byte total
    // (not the entry's self-reported/compressed size) drives the check.
    byte[] content = new byte[10_000];
    Files.write(src.resolve("NameUsage.tsv"), content);
    ColdpZip.zipFolder(src, zip);

    try (var in = Files.newInputStream(zip)) {
      assertThatThrownBy(() -> ColdpZip.extractToTemp(in, dest, 1_000L))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("1000 bytes");
    }
  }

  @Test
  void archiveWithinByteCapExtractsNormally(@TempDir Path tmp) throws IOException {
    Path src = tmp.resolve("src");
    Files.createDirectories(src);
    Path dest = tmp.resolve("dest");
    Path zip = tmp.resolve("small.zip");

    byte[] content = "ID\tscientificName\n1\tAbies alba\n".getBytes(StandardCharsets.UTF_8);
    Files.write(src.resolve("NameUsage.tsv"), content);
    ColdpZip.zipFolder(src, zip);

    try (var in = Files.newInputStream(zip)) {
      ColdpZip.extractToTemp(in, dest, 1_000_000L);
    }
    assertThat(dest.resolve("NameUsage.tsv")).exists();
    assertThat(Files.readAllBytes(dest.resolve("NameUsage.tsv"))).isEqualTo(content);
  }
}
