package com.shiwushop.commerce.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public interface AlipayGateway {
    boolean configured();
    String appId();
    String sellerId();
    String channel();
    QrCode qrCode(String outTradeNo, BigDecimal amount, LocalDateTime expiresAt);
    boolean verify(Map<String, String> form);
    TradeResult query(String outTradeNo);
    boolean close(String outTradeNo);
    CancelResult cancel(String outTradeNo);

    enum CancelResult { CLOSED, NO_TRADE, UNRESOLVED }

    record QrCode(String value, LocalDateTime expiresAt) {}

    record TradeResult(String status, String outTradeNo, String tradeNo, BigDecimal amount) {}
}
