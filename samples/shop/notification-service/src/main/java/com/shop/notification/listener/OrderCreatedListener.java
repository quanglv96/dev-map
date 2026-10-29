package com.shop.notification.listener;

import com.shop.notification.service.NotificationService;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCreatedListener {

  private final NotificationService notifications;

  public OrderCreatedListener(NotificationService notifications) {
    this.notifications = notifications;
  }

  @KafkaListener(topics = "order.created")
  public void onOrderCreated(Map<String, Object> event) {
    notifications.orderCreated(((Number) event.get("orderId")).longValue(), String.valueOf(event.get("customerId")));
  }
}
