package org.catalogueoflife.editor.name;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// Public, unauthenticated PDF download at coldp.pdf.base-url's default path -- SecurityConfig
// permits /pdf/** for exactly this reason: a hosted reference PDF needs to be citable/linkable by
// anyone, the same way ReferenceColdpWriter embeds its URL into an exported archive's `link` column.
// Mirrors ExportRunController's /{runId}/file streaming shape (FileSystemResource + Content-
// Disposition), just at the single top-level /pdf/{filename} route instead of a project-scoped one.
@RestController
public class PdfController {

  private static final MediaType APPLICATION_PDF = MediaType.valueOf("application/pdf");

  private final PdfService pdfService;

  public PdfController(PdfService pdfService) {
    this.pdfService = pdfService;
  }

  @GetMapping("/pdf/{filename}")
  public ResponseEntity<Resource> get(@PathVariable String filename) {
    Path resolved = pdfService.resolve(filename);
    if (!Files.isRegularFile(resolved)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "pdf not found");
    }
    Resource resource = new FileSystemResource(resolved);
    ContentDisposition disposition = ContentDisposition.inline().filename(filename).build();
    return ResponseEntity.ok()
        .contentType(APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .contentLength(resolved.toFile().length())
        .body(resource);
  }
}
