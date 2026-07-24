package org.catalogueoflife.editor.coldp.imprt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

// A Darwin Core Archive upload turned into a ColDP staging archive: the .zip is extracted
// (zip-bomb-guarded, like ColdpArchiveAdapter) into a scratch dir, then DwcaToColdp reads it with
// dwca-io and writes NameUsage.tsv + child TSVs + metadata.yaml into `dir`. The scratch dir is
// always cleaned up. Detection (meta.xml sniff) happens upstream in ImportRunService.start.
@Component
public class DwcaAdapter implements SourceFormatAdapter {

  private final DwcaToColdp converter;

  public DwcaAdapter(DwcaToColdp converter) {
    this.converter = converter;
  }

  @Override
  public SourceFormat format() {
    return SourceFormat.DWCA;
  }

  @Override
  public void materialize(MultipartFile file, Path dir, String title, long maxBytes) throws IOException {
    Path src = Files.createTempDirectory("dwca-src-");
    try {
      try (InputStream in = file.getInputStream()) {
        ColdpZip.extractToTemp(in, src, maxBytes);
      }
      converter.convert(src, dir, title);
    } finally {
      deleteRecursively(src);
    }
  }

  private static void deleteRecursively(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException ignored) {
          // best-effort cleanup of the scratch extraction dir
        }
      });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
