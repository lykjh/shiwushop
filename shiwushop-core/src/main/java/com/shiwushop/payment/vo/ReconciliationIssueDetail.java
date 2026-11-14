package com.shiwushop.payment.vo;

import com.shiwushop.payment.entity.*;

import java.util.List;

public record ReconciliationIssueDetail(
    /** 对账差异详情。 */
    ReconciliationIssue issue,
    /** 人工处理所依据的凭据。 */
    List<ReconciliationEvidence> evidence,
    /** 审计事件列表。 */
    List<ReconciliationAudit> audit
) {
}
