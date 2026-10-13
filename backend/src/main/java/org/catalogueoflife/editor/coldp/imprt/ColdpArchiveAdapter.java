package org.catalogueoflife.editor.coldp.imprt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata.ColdpMetadataDto;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

// The native format: a ColDP .zip is extracted (zip-bomb-guarded) into the dir. A non-blank `title`
// overrides the archive's own metadata.yaml title, so the same archive can be imported more than
// once (project titles are unique per user) -- e.g. the help's "Try it with demo data" playground.
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
    if (title != null && !title.isBlank()) {
      ColdpMetadataDto md = Files.isRegularFile(dir.resolve(ColdpMetadata.FILENAME))
          ? ColdpMetadata.read(dir)
          : new ColdpMetadataDto(null, null, null, null, null, null);
      ColdpMetadata.write(dir, new ColdpMetadataDto(title.trim(), md.alias(), md.description(),
          md.license(), md.geographicScope(), md.taxonomicScope()));
    }
  }
}
