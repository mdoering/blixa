package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

// Plain unit test (no Spring context) -- PdfService's constructor takes plain dir/max-bytes values,
// same as ImportRunService's own @Value-driven ctor. Mirrors the plan's 4 required cases: bad
// upload rejected 400, oversize rejected 413, path-traversal blocked 400, valid pdf round-trips.
class PdfServiceTest {

  private static final byte[] VALID_PDF =
      "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);

  @Test
  void rejectsFileThatIsNeitherPdfContentTypeNorMagic(@TempDir Path tmp) {
    PdfService service = new PdfService(tmp.toString(), 1_000_000);
    MockMultipartFile file = new MockMultipartFile("file", "not-a-pdf.txt", "text/plain",
        "just some text, definitely not a pdf".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> service.store(1, 1, file))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void rejectsOversizeFile(@TempDir Path tmp) {
    PdfService service = new PdfService(tmp.toString(), 10);
    MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", VALID_PDF);

    assertThatThrownBy(() -> service.store(1, 1, file))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
  }

  @Test
  void resolveBlocksPathTraversal(@TempDir Path tmp) {
    PdfService service = new PdfService(tmp.toString(), 1_000_000);

    assertThatThrownBy(() -> service.resolve("../etc/passwd"))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    assertThatThrownBy(() -> service.resolve("../../../etc/passwd"))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void storesAndResolvesAValidPdf(@TempDir Path tmp) throws Exception {
    PdfService service = new PdfService(tmp.toString(), 1_000_000);
    MockMultipartFile file = new MockMultipartFile("file", "reprint.pdf", "application/pdf", VALID_PDF);

    String filename = service.store(3, 42, file);

    assertThat(filename).startsWith("p3-r42-").endsWith(".pdf");
    Path resolved = service.resolve(filename);
    assertThat(resolved).exists();
    assertThat(Files.readAllBytes(resolved)).isEqualTo(VALID_PDF);
  }

  @Test
  void rejectsNonPdfBytesEvenWithDeclaredPdfContentType(@TempDir Path tmp) {
    PdfService service = new PdfService(tmp.toString(), 1_000_000);
    // A declared Content-Type is advisory only -- a client can lie and label arbitrary bytes
    // application/pdf. The %PDF- magic check must still reject this regardless.
    MockMultipartFile file = new MockMultipartFile("file", "not-really.pdf", "application/pdf",
        "just some text, definitely not a pdf".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> service.store(1, 1, file))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void acceptsPdfMagicEvenWithWrongContentType(@TempDir Path tmp) {
    PdfService service = new PdfService(tmp.toString(), 1_000_000);
    // Some browsers/clients send application/octet-stream (or nothing at all) for a raw file input
    // -- the %PDF- magic bytes alone must be enough.
    MockMultipartFile file =
        new MockMultipartFile("file", "reprint.pdf", "application/octet-stream", VALID_PDF);

    String filename = service.store(1, 1, file);

    assertThat(filename).endsWith(".pdf");
  }

  @Test
  void deleteIsBestEffortForAMissingFile(@TempDir Path tmp) {
    PdfService service = new PdfService(tmp.toString(), 1_000_000);
    // no exception for a file that was never stored
    service.delete("never-existed.pdf");
    service.delete(null);
  }
}
