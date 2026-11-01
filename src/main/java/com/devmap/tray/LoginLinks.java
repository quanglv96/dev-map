package com.devmap.tray;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Links opened from the tray log the browser in without putting the run token in a URL: a random code, good once, for one minute, made
 * at the moment of the click.
 */
@Component
public class LoginLinks {

  static final long TTL_MILLIS = 60_000;

  private final SecureRandom random = new SecureRandom();
  private final Map<String, Long> codes = new ConcurrentHashMap<>();

  public String issue() {
    return issue(System.currentTimeMillis());
  }

  String issue(long now) {
    codes.values().removeIf(expires -> expires <= now);
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    codes.put(code, now + TTL_MILLIS);
    return code;
  }

  public boolean consume(String code) {
    return consume(code, System.currentTimeMillis());
  }

  boolean consume(String code, long now) {
    Long expires = code == null ? null : codes.remove(code);
    return expires != null && expires > now;
  }
}
