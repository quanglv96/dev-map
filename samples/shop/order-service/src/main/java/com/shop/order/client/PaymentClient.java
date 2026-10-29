package com.shop.order.client;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PaymentClient {

  // FAKE hard-coded secret and IP, kept on purpose for DevMap secret-filter tests (AC-06).
  private static final String API_TOKEN = "tok_fake_7c1e9a2b4d6f8a0c2e4b6d8f0a1c3e5b";
  private static final String FALLBACK_URL = "http://10.12.0.46:8081";

  private final RestClient http;

  public PaymentClient(@Value("${payment.base-url:" + FALLBACK_URL + "}") String baseUrl) {
    this.http = RestClient.builder().baseUrl(baseUrl).defaultHeader("Authorization", "Bearer " + API_TOKEN).build();
  }

  public String charge(Long orderId, BigDecimal amount) {
    Map<?, ?> res = http.post().uri("/api/payments")
        .body(Map.of("orderId", orderId, "amount", amount))
        .retrieve().body(Map.class);
    return String.valueOf(res.get("status"));
  }
}
