package com.shiwushop.commerce.payment;

import java.math.BigDecimal;

public interface RefundGateway {
    /** 判断退款渠道是否具备所需商户配置。 */
    boolean configured();
    /** 用固定退款请求号发起退款，供失败重试时保持幂等。 */
    Result refund(String outTradeNo, String tradeNo, String outRequestNo, BigDecimal amount);
    /** 按原交易及退款请求号核对渠道退款结果。 */
    Result query(String outTradeNo, String tradeNo, String outRequestNo);

    record Result(boolean succeeded, String outTradeNo, String tradeNo,
                  String outRequestNo, BigDecimal amount) {}
}
