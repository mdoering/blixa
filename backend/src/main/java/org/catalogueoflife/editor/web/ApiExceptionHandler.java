package org.catalogueoflife.editor.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> handle(ResponseStatusException ex) {
    return ResponseEntity.status(ex.getStatusCode())
        .body(Map.of("error", ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleBadArg(IllegalArgumentException ex) {
    return ResponseEntity.badRequest().body(Map.of("error", "invalid value: " + ex.getMessage()));
  }

  // Spring's multipart parser rejects an oversize upload with this exception BEFORE the request
  // ever reaches a controller method (it's thrown while resolving the MultipartFile argument), so
  // ImportRunService's own file.getSize() > maxBytes check never gets a chance to run for uploads
  // that exceed spring.servlet.multipart.max-file-size/max-request-size. Without this handler it
  // would fall through to the default error page instead of the same {"error": ...} 413 contract
  // the rest of the API uses.
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, Object>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
    return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
        .body(Map.of("error", "uploaded file is too large"));
  }

  // @Valid DTO failures (e.g. @NotBlank) go through Spring's own MethodArgumentNotValidException
  // rather than ResponseStatusException; unify both onto the same {"error": <message>} 400 body
  // so the frontend has a single 400 contract to handle.
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
    FieldError fieldError = ex.getBindingResult().getFieldError();
    String message = fieldError == null
        ? "validation failed"
        : fieldError.getField() + ": " + fieldError.getDefaultMessage();
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
  }
}
