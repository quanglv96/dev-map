package com.devmap.ticket;

/** An action reserved for the PMO (NFR-03) was attempted by someone else; the API answers 403. */
public class PmoOnlyException extends RuntimeException {

  public PmoOnlyException(String message) {
    super(message);
  }
}
