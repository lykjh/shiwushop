package com.shiwushop.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("payment_reconciliation_evidence")
public class ReconciliationEvidence {
    /** 对账凭据 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long issueId, // 对账差异记录 ID。
        actorId; // 本次操作人用户 ID。
    private String billEvidence, // 账单侧核对凭据。
        localEvidence, // 本地订单及支付记录核对凭据。
        channelQueryEvidence, // 支付渠道查询结果凭据。
        requestId; // 请求追踪标识。
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
}
