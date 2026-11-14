package com.shiwushop.trade.entity.payment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shiwushop.trade.entity.status.PaymentAttemptStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("payment_attempts")
public class PaymentAttempt {
    /** 支付尝试 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联订单 ID。 */
    private Long orderId;
    /** 支付渠道。 */
    private String channel;
    /** 本系统提交给支付渠道的商户交易号。 */
    private String outTradeNo;
    /** 支付宝侧交易号。 */
    private String alipayTradeNo;
    /** 支付渠道应用标识。 */
    private String appId;
    /** 支付渠道收款商户标识。 */
    private String sellerId;
    /** 业务金额，单位元。 */
    private BigDecimal amount;
    /** 支付尝试状态，取值见 PaymentAttemptStatus。 */
    private PaymentAttemptStatus status;
    /** 渠道是否已保证该二维码按约定时间失效。 */
    private Boolean qrTimeoutGuaranteed;
    /** 支付二维码失效时间。 */
    private LocalDateTime qrExpiresAt;
    /** 支付二维码内容。 */
    private String qrCode;
    /** 创建支付二维码的工作租约令牌。 */
    private String qrClaimToken;
    /** 创建支付二维码的工作租约到期时间。 */
    private LocalDateTime qrClaimExpiresAt;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 支付或结算完成时间。 */
    private LocalDateTime paidAt;
    /** 业务关闭时间。 */
    private LocalDateTime closedAt;
}
