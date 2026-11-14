package com.shiwushop.aftersale.entity.refund;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shiwushop.aftersale.entity.status.RefundAttemptStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("refund_attempts")
public class RefundAttempt {
    /** 退款尝试 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联售后单 ID。 */
    private Long caseId;
    /** 关联支付尝试 ID。 */
    private Long paymentAttemptId;
    /** 本系统提交给支付渠道的退款请求号。 */
    private String outRequestNo;
    /** 支付渠道返回的退款编号。 */
    private String providerRefundNo;
    /** 业务金额，单位元。 */
    private BigDecimal amount;
    /** 退款尝试状态，取值见 RefundAttemptStatus。 */
    private RefundAttemptStatus status;
    /** 活动退款尝试对应的售后单唯一占位键；非表字段，不直接持久化。 */
    @TableField(exist = false)
    private Long activeCaseId;
    /** 请求发起时间。 */
    private LocalDateTime requestedAt;
    /** 处理结果确认时间。 */
    private LocalDateTime resolvedAt;
}
