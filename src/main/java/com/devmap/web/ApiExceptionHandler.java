package com.devmap.web;

import com.devmap.ticket.PmoOnlyException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns service exceptions into {"error": "..."} the UI can show as is. */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
    return error(HttpStatus.BAD_REQUEST, e);
  }

  @ExceptionHandler(PmoOnlyException.class)
  public ResponseEntity<Map<String, String>> forbidden(PmoOnlyException e) {
    return error(HttpStatus.FORBIDDEN, e);
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
    return error(HttpStatus.NOT_FOUND, e);
  }

  @ExceptionHandler({UncheckedIOException.class, IllegalStateException.class})
  public ResponseEntity<Map<String, String>> failure(RuntimeException e) {
    return error(HttpStatus.INTERNAL_SERVER_ERROR, e);
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, Exception e) {
    return ResponseEntity.status(status).body(Map.of("error", String.valueOf(e.getMessage())));
  }
}
