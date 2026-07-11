package org.catalogueoflife.editor.coldp.imprt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

// The native format: a ColDP .zip is extracted (zip-bomb-guarded) into the dir. `title` is ignored
// -- a ColDP archive carries its own metadata.yaml.
@Component
public class ColdpArchiveAdapter implements SourceFormatAdapter {

  @Override
  public SourceFormat format() {
    return SourceFormat.COLDP;
  }

  @Override
  public void materialize(MultipartFile file, Path dir, String title, long maxBytes) throws IOException {
    try (InputStream in = file.getInputStream()) {
      ColdpZip.extractToTemp(in, dir, maxBytes);
    }
  }
}
