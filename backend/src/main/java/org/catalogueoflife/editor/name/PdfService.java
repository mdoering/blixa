package org.catalogueoflife.editor.name;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

// Disk storage for one PDF per reference (Task 2 of the Reference PDF+RIS plan). Mirrors
// ImportRunService's multipart size-gating (constructor-created dir, CONTENT_TOO_LARGE on an
// oversize upload) and ExportRunController's file-streaming shape, but at the single-file scale of
// a reference PDF rather than a whole ColDP archive/export zip. Every stored filename is
// app-generated (store()) -- resolve()'s path-traversal guard exists for the OTHER direction, an
// attacker-controlled filename arriving at GET /pdf/{filename} (see PdfController).
@Service
public class PdfService {

  // %PDF- is the canonical PDF file-header magic (PDF spec section 7.5.2); checked as a fallback
  // when the multipart part's declared Content-Type isn't (or lies about being) application/pdf --
  // browsers/clients are inconsistent about setting it for a raw file input.
  private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
  private static final String APPLICATION_PDF = "application/pdf";

  private final Path dir;
  private final long maxBytes;

  public PdfService(@Value("${coldp.pdf.dir}") String dir, @Value("${coldp.pdf.max-bytes}") long maxBytes) {
    this.dir = Path.of(dir);
    this.maxBytes = maxBytes;
    try {
      Files.createDirectories(this.dir);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to create pdf dir " + dir, e);
    }
  }

  /**
   * Validates and writes {@code file} into the pdf dir, returning its generated filename
   * (never a path -- callers persist just this on {@code reference.pdf}).
   */
  public String store(int projectId, int refId, MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required");
    }
    if (file.getSize() > maxBytes) {
      throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
          "pdf exceeds " + maxBytes + " bytes");
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read uploaded pdf", e);
    }
    boolean declaredPdf = APPLICATION_PDF.equals(file.getContentType());
    boolean magicPdf = startsWithPdfMagic(bytes);
    if (!declaredPdf && !magicPdf) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is not a PDF");
    }
    String filename = "p" + projectId + "-r" + refId + "-" + shortUuid() + ".pdf";
    try {
      Files.write(dir.resolve(filename), bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to store pdf " + filename, e);
    }
    return filename;
  }

  /** Best-effort delete; a missing file or any IO failure is silently swallowed. */
  public void delete(String filename) {
    if (filename == null) {
      return;
    }
    Path resolved = dir.resolve(filename).normalize();
    if (!resolved.startsWith(dir.normalize())) {
      // Never happens for a filename this class itself generated; guard anyway rather than delete
      // outside the pdf dir on a corrupt/tampered `reference.pdf` value.
      return;
    }
    try {
      Files.deleteIfExists(resolved);
    } catch (IOException e) {
      // best-effort: an orphaned file on disk is a cheap, silent leak; failing the caller's request
      // (attachPdf replacing it, or removePdf clearing the column) over cleanup is not worth it.
    }
  }

  /**
   * Resolves {@code filename} to a path inside the pdf dir, rejecting any attempt to escape it
   * (e.g. {@code ../../etc/passwd}) with a 400. Does NOT check the file exists -- callers (e.g.
   * PdfController) do their own 404 on a resolved-but-absent path.
   */
  public Path resolve(String filename) {
    Path resolved = dir.resolve(filename).normalize();
    if (!resolved.startsWith(dir.normalize())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid filename");
    }
    return resolved;
  }

  private static boolean startsWithPdfMagic(byte[] bytes) {
    if (bytes.length < PDF_MAGIC.length) {
      return false;
    }
    for (int i = 0; i < PDF_MAGIC.length; i++) {
      if (bytes[i] != PDF_MAGIC[i]) {
        return false;
      }
    }
    return true;
  }

  private static String shortUuid() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }
}
