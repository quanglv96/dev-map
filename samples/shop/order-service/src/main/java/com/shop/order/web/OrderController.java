package com.shop.order.web;

import com.shop.order.domain.Order;
import com.shop.order.service.OrderService;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

  public record CreateOrder(String customerId, BigDecimal amount) {
  }

  private final OrderService service;

  public OrderController(OrderService service) {
    this.service = service;
  }

  @PostMapping
  public Order create(@RequestBody CreateOrder req) {
    return service.create(req.customerId(), req.amount());
  }
}
