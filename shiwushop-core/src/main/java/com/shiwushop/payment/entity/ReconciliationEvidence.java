package com.shiwushop.payment.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("payment_reconciliation_evidence")
public class ReconciliationEvidence {
 @TableId(type=IdType.AUTO) private Long id;
 private Long issueId, actorId;
 private String billEvidence, localEvidence, channelQueryEvidence, requestId;
 private LocalDateTime createdAt;
}
