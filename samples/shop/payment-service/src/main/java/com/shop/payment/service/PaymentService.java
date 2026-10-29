package com.shop.payment.service;

import com.shop.payment.domain.Payment;
import com.shop.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

  private final PaymentRepository payments;

  public PaymentService(PaymentRepository payments) {
    this.payments = payments;
  }

  public Payment pay(Long orderId, BigDecimal amount) {
    String status = amount.signum() > 0 ? "PAID" : "FAILED";
    return payments.save(new Payment(orderId, amount, status));
  }

  public Payment get(Long id) {
    return payments.findById(id).orElseThrow(() -> new NoSuchElementException("payment " + id));
  }
}
