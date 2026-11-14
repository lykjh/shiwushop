package com.shiwushop.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("payment_reconciliation_issue_audit")
public class ReconciliationAudit {
    /** 对账审计事件 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long issueId, // 对账差异记录 ID。
        actorId, // 本次操作人用户 ID。
        evidenceId; // 关联凭据记录 ID。
    private String action, // 审计操作代码。
        requestId, // 请求追踪标识。
        conclusionType, // 人工处理结论类型。
        conclusion, // 人工处理结论说明。
        manualHandlingReference; // 线下人工处置凭据引用。
    /** 审计事件发生时间。 */
    private LocalDateTime occurredAt;
}
