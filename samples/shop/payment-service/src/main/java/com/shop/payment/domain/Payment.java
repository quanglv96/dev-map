package com.shop.payment.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.math.BigDecimal;

@Entity
public class Payment {
  @Id
  @GeneratedValue
  private Long id;
  private Long orderId;
  private BigDecimal amount;
  private String status;

  protected Payment() {
  }

  public Payment(Long orderId, BigDecimal amount, String status) {
    this.orderId = orderId;
    this.amount = amount;
    this.status = status;
  }

  public Long getId() { return id; }
  public Long getOrderId() { return orderId; }
  public BigDecimal getAmount() { return amount; }
  public String getStatus() { return status; }
}
