package com.shiwushop.commerce.payment;

import java.math.BigDecimal;

public interface RefundGateway {
    boolean configured();
    Result refund(String outTradeNo, String tradeNo, String outRequestNo, BigDecimal amount);
    Result query(String outTradeNo, String tradeNo, String outRequestNo);

    record Result(boolean succeeded, String outTradeNo, String tradeNo,
                  String outRequestNo, BigDecimal amount) {}
}
