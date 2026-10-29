package com.shop.payment.web;

import com.shop.payment.domain.Payment;
import com.shop.payment.service.PaymentService;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

  public record PaymentRequest(Long orderId, BigDecimal amount) {
  }

  private final PaymentService service;

  public PaymentController(PaymentService service) {
    this.service = service;
  }

  @PostMapping
  public Payment create(@RequestBody PaymentRequest req) {
    return service.pay(req.orderId(), req.amount());
  }

  @GetMapping("/{id}")
  public Payment get(@PathVariable Long id) {
    return service.get(id);
  }
}
