package com.shop.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

  public void orderCreated(Long orderId, String customerId) {
    LOG.info("Notify customer {} about order {}", customerId, orderId);
  }
}
