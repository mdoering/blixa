package org.catalogueoflife.editor.coldp.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata.ColdpMetadataDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@link ColdpMetadata#write} / {@link ColdpMetadata#read} round-trip a {@code
 * metadata.yaml} using the six ColDP metadata keys our {@code project} maps: {@code title},
 * {@code alias}, {@code description}, {@code license}, {@code geographicScope}, {@code
 * taxonomicScope}.
 */
class ColdpMetadataTest {

  @Test
  void roundTripFullyPopulated(@TempDir Path dir) throws IOException {
    ColdpMetadataDto dto =
        new ColdpMetadataDto(
            "Full Title", "Alias", "A multi word\ndescription.", "CC0-1.0", "global", "Fishes");

    ColdpMetadata.write(dir, dto);
    ColdpMetadataDto read = ColdpMetadata.read(dir);

    assertThat(read).isEqualTo(dto);
  }

  @Test
  void readToleratesMissingAndUnknownKeys(@TempDir Path dir) throws IOException {
    String yaml =
        """
        title: Minimal Title
        contact: foo
        """;
    Files.writeString(dir.resolve("metadata.yaml"), yaml, StandardCharsets.UTF_8);

    ColdpMetadataDto read = ColdpMetadata.read(dir);

    assertThat(read.title()).isEqualTo("Minimal Title");
    assertThat(read.alias()).isNull();
    assertThat(read.description()).isNull();
    assertThat(read.license()).isNull();
    assertThat(read.geographicScope()).isNull();
    assertThat(read.taxonomicScope()).isNull();
  }

  @Test
  void writeOmitsNullValuedKeys(@TempDir Path dir) throws IOException {
    ColdpMetadataDto dto =
        new ColdpMetadataDto("Only Title And License", null, null, "CC-BY-4.0", null, null);

    ColdpMetadata.write(dir, dto);

    String content = Files.readString(dir.resolve("metadata.yaml"), StandardCharsets.UTF_8);
    assertThat(content).contains("title:").contains("license:");
    assertThat(content)
        .doesNotContain("alias:")
        .doesNotContain("description:")
        .doesNotContain("geographicScope:")
        .doesNotContain("taxonomicScope:");
  }

  @Test
  void readThrowsWhenMetadataFileMissing(@TempDir Path dir) {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ColdpMetadata.read(dir))
        .isInstanceOf(IOException.class);
  }

  @Test
  void readRejectsMaliciousGlobalTag(@TempDir Path dir) throws IOException {
    // A crafted metadata.yaml using a "!!<fqcn>" global tag to try to instantiate an arbitrary
    // classpath class. With the unrestricted `new Yaml()` this would be resolvable and
    // constructed; with SafeConstructor it must be rejected.
    String yaml = "title: !!javax.script.ScriptEngineManager []\n";
    Files.writeString(dir.resolve("metadata.yaml"), yaml, StandardCharsets.UTF_8);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ColdpMetadata.read(dir))
        .isInstanceOf(org.yaml.snakeyaml.error.YAMLException.class);
  }
}
