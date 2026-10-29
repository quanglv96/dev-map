package com.shop.order.web;

import com.shop.order.domain.Order;
import com.shop.order.repository.OrderRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Layering violation kept on purpose (AC-10): a Controller calling a Repository directly. */
@RestController
public class OrderReportController {

  private final OrderRepository orders;

  public OrderReportController(OrderRepository orders) {
    this.orders = orders;
  }

  @GetMapping("/api/customers/{customerId}/orders")
  public List<Order> byCustomer(@PathVariable String customerId) {
    return orders.findByCustomerId(customerId);
  }
}
