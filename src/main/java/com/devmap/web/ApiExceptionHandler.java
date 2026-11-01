package com.devmap.web;

import com.devmap.ticket.PmoOnlyException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns service exceptions into {"error": "..."} the UI can show as is. */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
    return error(HttpStatus.BAD_REQUEST, e);
  }

  /** Its cause is an IOException, so without this exact match a bad body would land in clientGone() and answer 200. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Dữ liệu gửi lên không hợp lệ"));
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

  /** A browser tab closed under an open SSE stream: the client is gone, nothing to answer or log. */
  @ExceptionHandler(IOException.class)
  public void clientGone() {
    // intentionally empty
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, Exception e) {
    return ResponseEntity.status(status).body(Map.of("error", String.valueOf(e.getMessage())));
  }
}
