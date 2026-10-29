package com.devmap.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** NFR-08: random token generated on every start; required on every HTTP request. */
@Component
public class ServerToken {

  private final String value;

  public ServerToken() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public String value() {
    return value;
  }

  public boolean matches(String candidate) {
    return candidate != null
        && MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
  }
}
