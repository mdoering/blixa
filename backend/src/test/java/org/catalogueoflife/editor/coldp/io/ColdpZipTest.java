package org.catalogueoflife.editor.coldp.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import life.catalogue.coldp.ColdpTerm;
import life.catalogue.csv.ColdpReader;
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

  private static void put(ZipOutputStream zos, String name, String content) throws IOException {
    zos.putNextEntry(new ZipEntry(name));
    zos.write(content.getBytes(StandardCharsets.UTF_8));
    zos.closeEntry();
  }

  // A Finder-zipped archive: the real ColDP files are wrapped in a folder AND there is a __MACOSX
  // sibling (+ .git, Thumbs.db, .DS_Store, desktop.ini). Leaving __MACOSX/.git would leave two
  // top-level dirs, defeating the CLB reader's single-subfolder descent -> "no data files found".
  private static byte[] finderStyleArchive() throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(bos)) {
      put(zos, "hominidae/NameUsage.tsv",
          "ID\tscientificName\trank\tstatus\n1\tHomo sapiens\tspecies\taccepted\n");
      put(zos, "hominidae/metadata.yaml", "title: Hominidae\n");
      put(zos, "hominidae/.DS_Store", "  junk");
      put(zos, "__MACOSX/hominidae/._NameUsage.tsv", "applesingle-junk");
      put(zos, ".git/config", "[core]\n");
      put(zos, "Thumbs.db", "thumbnail-cache");
      put(zos, "hominidae/desktop.ini", "[.ShellClassInfo]\n");
    }
    return bos.toByteArray();
  }

  @Test
  void stripsCruftAndFlattensWrapperSoTheReaderFindsData(@TempDir Path dir) throws IOException {
    ColdpZip.extractToTemp(new ByteArrayInputStream(finderStyleArchive()), dir);

    // the OS/VCS cruft is dropped, and the lone `hominidae/` wrapper is flattened, so the ColDP
    // data files end up directly at the extract root
    assertThat(Files.exists(dir.resolve("NameUsage.tsv"))).isTrue();
    assertThat(Files.exists(dir.resolve("metadata.yaml"))).isTrue();
    assertThat(Files.exists(dir.resolve("hominidae"))).isFalse();
    assertThat(Files.exists(dir.resolve("__MACOSX"))).isFalse();
    assertThat(Files.exists(dir.resolve(".git"))).isFalse();
    assertThat(Files.exists(dir.resolve("Thumbs.db"))).isFalse();
    assertThat(Files.exists(dir.resolve(".DS_Store"))).isFalse();
    assertThat(Files.exists(dir.resolve("desktop.ini"))).isFalse();

    // and the CLB reader finds the schema at the (now flat) root
    ColdpReader reader = ColdpReader.from(dir);
    assertThat(reader.hasSchema(ColdpTerm.NameUsage)).isTrue();
  }
}
