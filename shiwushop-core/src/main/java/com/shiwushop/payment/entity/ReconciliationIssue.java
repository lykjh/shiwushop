package com.shiwushop.payment.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("payment_reconciliation_issues")
public class ReconciliationIssue {
 @TableId(type=IdType.AUTO) private Long id;
 private Long orderId, paymentAttemptId, claimedBy, resolvedEvidenceId;
 private String issueType, status, summary, conclusionType, conclusion, manualHandlingReference;
 private LocalDateTime detectedAt, resolvedAt, createdAt, updatedAt;
}
