package org.catalogueoflife.editor.coldp.imprt;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

// A GBIF text-tree upload converted into a ColDP staging archive (NameUsage.tsv + metadata.yaml).
@Component
public class TxtTreeAdapter implements SourceFormatAdapter {

  private final TxtTreeToColdp converter;

  public TxtTreeAdapter(TxtTreeToColdp converter) {
    this.converter = converter;
  }

  @Override
  public SourceFormat format() {
    return SourceFormat.TXTREE;
  }

  @Override
  public void materialize(MultipartFile file, Path dir, String title, long maxBytes) throws IOException {
    try (Reader r = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
      converter.convert(r, dir, title);
    }
  }
}
