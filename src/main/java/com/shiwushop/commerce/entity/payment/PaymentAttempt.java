package com.shiwushop.commerce.entity.payment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shiwushop.entity.status.PaymentAttemptStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("payment_attempts")
public class PaymentAttempt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String channel;
    private String outTradeNo;
    private String alipayTradeNo;
    private String appId;
    private String sellerId;
    private BigDecimal amount;
    private PaymentAttemptStatus status;
    private Boolean qrTimeoutGuaranteed;
    private LocalDateTime qrExpiresAt;
    private String qrCode;
    private String qrClaimToken;
    private LocalDateTime qrClaimExpiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
    private LocalDateTime closedAt;
}
