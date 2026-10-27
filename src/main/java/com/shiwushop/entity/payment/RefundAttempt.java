package com.shiwushop.entity.payment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shiwushop.entity.status.RefundAttemptStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("refund_attempts")
public class RefundAttempt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private Long paymentAttemptId;
    private String outRequestNo;
    private String providerRefundNo;
    private BigDecimal amount;
    private RefundAttemptStatus status;
    @TableField(exist = false)
    private Long activeCaseId;
    private LocalDateTime requestedAt;
    private LocalDateTime resolvedAt;
}
