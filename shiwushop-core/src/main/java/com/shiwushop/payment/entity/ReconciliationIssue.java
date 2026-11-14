package com.shiwushop.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("payment_reconciliation_issues")
public class ReconciliationIssue {
    /** 对账差异 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId, // 关联订单 ID。
        paymentAttemptId, // 关联支付尝试 ID。
        claimedBy, // 认领对账差异的用户 ID。
        resolvedEvidenceId; // 关闭对账差异时采用的凭据 ID。
    private String issueType, // 对账差异类型。
        status, // 对账差异状态：OPEN 未解决、RESOLVED 已解决。
        summary, // 审计或差异摘要。
        conclusionType, // 人工处理结论类型。
        conclusion, // 人工处理结论说明。
        manualHandlingReference; // 线下人工处置凭据引用。
    private LocalDateTime detectedAt, // 对账差异发现时间。
        resolvedAt, // 处理结果确认时间。
        createdAt, // 记录创建时间。
        updatedAt; // 记录最近更新时间。
}
