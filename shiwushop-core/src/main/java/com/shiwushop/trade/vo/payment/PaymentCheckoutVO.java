package com.shiwushop.trade.vo.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付业务只提供展示数据，HTML 与二维码图形由 Web 入口渲染。 */
public record PaymentCheckoutVO(
    /** 支付二维码内容。 */
    String qrCode,
    /** 业务金额，单位元。 */
    BigDecimal amount,
    /** 订单付款截止时间。 */
    LocalDateTime payExpiresAt,
    /** 支付渠道。 */
    String channel
) {
}
