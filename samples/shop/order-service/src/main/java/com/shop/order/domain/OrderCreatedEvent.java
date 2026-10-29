package com.shop.order.domain;

import java.math.BigDecimal;

public record OrderCreatedEvent(Long orderId, String customerId, BigDecimal amount) {
}
