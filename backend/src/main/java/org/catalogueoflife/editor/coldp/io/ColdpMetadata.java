package org.catalogueoflife.editor.coldp.io;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads and writes a ColDP archive's project-level {@code metadata.yaml} using SnakeYAML.
 *
 * <p>ColDP's {@code metadata.yaml} supports many more keys in the wild (contact, creator,
 * keywords, etc. -- see the {@code metadata.yaml}/{@code metadata.json} conventions in the {@code
 * coldp} spec repo). This class maps only the six keys our {@code project} entity carries: {@code
 * title}, {@code alias}, {@code description}, {@code license}, {@code geographicScope}, {@code
 * taxonomicScope} -- all genuine top-level ColDP metadata.yaml keys.
 *
 * <p>{@link #read(Path)} ignores any other keys present in the file (unknown-key tolerant).
 * {@link #write(Path, ColdpMetadataDto)} emits only non-null fields, in clean YAML block style.
 */
public final class ColdpMetadata {

  public static final String FILENAME = "metadata.yaml";

  private ColdpMetadata() {}

  /**
   * The six ColDP metadata fields our {@code project} entity maps.
   *
   * @param title full dataset title
   * @param alias short, hopefully unique name for the dataset
   * @param description multi paragraph description / abstract of the dataset
   * @param license dataset license, e.g. {@code CC0-1.0}
   * @param geographicScope description of the geographical scope of the dataset
   * @param taxonomicScope taxonomic scope of the dataset
   */
  public record ColdpMetadataDto(
      String title,
      String alias,
      String description,
      String license,
      String geographicScope,
      String taxonomicScope) {}

  /**
   * Writes {@code dir/metadata.yaml}, emitting only the non-null fields of {@code md} in clean
   * YAML block style.
   *
   * @param dir the folder to write into; must already exist
   * @param md the metadata to write
   */
  public static void write(Path dir, ColdpMetadataDto md) throws IOException {
    Map<String, Object> data = new LinkedHashMap<>();
    putIfPresent(data, "title", md.title());
    putIfPresent(data, "alias", md.alias());
    putIfPresent(data, "description", md.description());
    putIfPresent(data, "license", md.license());
    putIfPresent(data, "geographicScope", md.geographicScope());
    putIfPresent(data, "taxonomicScope", md.taxonomicScope());

    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    Yaml yaml = new Yaml(options);

    Path file = dir.resolve(FILENAME);
    try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      yaml.dump(data, writer);
    }
  }

  private static void putIfPresent(Map<String, Object> data, String key, String value) {
    if (value != null) {
      data.put(key, value);
    }
  }

  /**
   * Parses {@code dir/metadata.yaml} into a {@link ColdpMetadataDto}. Unknown keys are ignored;
   * keys missing from the file map to a {@code null} field.
   *
   * @param dir the folder containing {@code metadata.yaml}
   * @throws NoSuchFileException if {@code dir/metadata.yaml} does not exist
   */
  public static ColdpMetadataDto read(Path dir) throws IOException {
    Path file = dir.resolve(FILENAME);
    if (!Files.isRegularFile(file)) {
      throw new NoSuchFileException(file.toString());
    }

    Yaml yaml = new Yaml();
    Map<String, Object> data;
    try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      Object loaded = yaml.load(reader);
      data = loaded instanceof Map<?, ?> m ? castKeys(m) : Map.of();
    }

    return new ColdpMetadataDto(
        str(data, "title"),
        str(data, "alias"),
        str(data, "description"),
        str(data, "license"),
        str(data, "geographicScope"),
        str(data, "taxonomicScope"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castKeys(Map<?, ?> m) {
    return (Map<String, Object>) m;
  }

  private static String str(Map<String, Object> data, String key) {
    return Objects.toString(data.get(key), null);
  }
}
