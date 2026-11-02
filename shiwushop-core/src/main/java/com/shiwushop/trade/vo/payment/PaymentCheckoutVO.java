package com.shiwushop.trade.vo.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付业务只提供展示数据，HTML 与二维码图形由 Web 入口渲染。 */
public record PaymentCheckoutVO(String qrCode, BigDecimal amount, LocalDateTime payExpiresAt, String channel) {}
