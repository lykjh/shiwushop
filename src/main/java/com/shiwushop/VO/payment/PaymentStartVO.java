package com.shiwushop.vo.payment;

public record PaymentStartVO(Long paymentId, Long orderId, String channel, String status, String payUrl) {}
