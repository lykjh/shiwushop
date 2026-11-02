package com.shiwushop.trade.vo.payment;

public record PaymentStartVO(Long paymentId, Long orderId, String channel, String status, String payUrl) {}
