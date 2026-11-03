package com.shiwushop.payment.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("payment_reconciliation_issue_audit")
public class ReconciliationAudit {
 @TableId(type=IdType.AUTO) private Long id;
 private Long issueId, actorId, evidenceId;
 private String action, requestId, conclusionType, conclusion, manualHandlingReference;
 private LocalDateTime occurredAt;
}
