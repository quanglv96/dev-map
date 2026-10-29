package com.shop.order.service;

import com.shop.order.client.PaymentClient;
import com.shop.order.domain.Order;
import com.shop.order.domain.OrderCreatedEvent;
import com.shop.order.repository.OrderRepository;
import java.math.BigDecimal;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

  public static final String TOPIC_ORDER_CREATED = "order.created";

  private final OrderRepository orders;
  private final PaymentClient payments;
  private final KafkaTemplate<String, Object> kafka;

  public OrderService(OrderRepository orders, PaymentClient payments, KafkaTemplate<String, Object> kafka) {
    this.orders = orders;
    this.payments = payments;
    this.kafka = kafka;
  }

  @Transactional
  public Order create(String customerId, BigDecimal amount) {
    Order order = orders.save(new Order(customerId, amount));
    order.setStatus(payments.charge(order.getId(), amount));
    kafka.send(TOPIC_ORDER_CREATED, new OrderCreatedEvent(order.getId(), customerId, amount));
    return order;
  }
}
