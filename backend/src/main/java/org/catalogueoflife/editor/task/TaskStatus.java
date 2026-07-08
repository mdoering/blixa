package org.catalogueoflife.editor.task;

import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Stored as the enum's name() (TEXT column, see V6__task.sql); the API speaks lowercase (matching
// Role's dbValue()/fromDb() convention) -- fromApi() is case-insensitive and rejects anything
// unrecognized with a 400 rather than an IllegalArgumentException bubbling up as a 500.
public enum TaskStatus {
  OPEN, CLOSED;

  public String apiValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static TaskStatus fromApi(String v) {
    try {
      return TaskStatus.valueOf(v.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status: " + v);
    }
  }
}
