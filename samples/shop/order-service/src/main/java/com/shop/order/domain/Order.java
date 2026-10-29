package com.shop.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "orders")
public class Order {
  @Id
  @GeneratedValue
  private Long id;
  private String customerId;
  private BigDecimal amount;
  private String status;

  protected Order() {
  }

  public Order(String customerId, BigDecimal amount) {
    this.customerId = customerId;
    this.amount = amount;
    this.status = "NEW";
  }

  public Long getId() { return id; }
  public String getCustomerId() { return customerId; }
  public BigDecimal getAmount() { return amount; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
}
