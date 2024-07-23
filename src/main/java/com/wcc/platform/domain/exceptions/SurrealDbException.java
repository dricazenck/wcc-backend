package com.wcc.platform.domain.exceptions;

/** Platform generic exception. */
public class SurrealDbException extends RuntimeException {

  public SurrealDbException(String message) {
    super(message);
  }
}
