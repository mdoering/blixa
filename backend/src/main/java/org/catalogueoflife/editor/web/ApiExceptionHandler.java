package org.catalogueoflife.editor.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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
