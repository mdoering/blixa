package org.catalogueoflife.editor.coldp.imprt;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;

// Turns an uploaded file into a ColDP-readable directory (containing at least NameUsage.tsv +
// metadata.yaml) that ImportRunService.run() then loads into a staging project.
public interface SourceFormatAdapter {
  SourceFormat format();

  void materialize(MultipartFile file, Path dir, String title, long maxBytes) throws IOException;
}
