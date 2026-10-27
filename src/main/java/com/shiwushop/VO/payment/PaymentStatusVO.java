package com.shiwushop.vo.payment;

import java.time.LocalDateTime;

public record PaymentStatusVO(Long orderId, String orderStatus, LocalDateTime payExpiresAt, Payment payment) {
    public record Payment(Long id, String channel, String status, LocalDateTime paidAt) {}
}
