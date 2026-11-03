package com.shiwushop.payment.vo;
import com.shiwushop.payment.entity.*;
import java.util.List;
public record ReconciliationIssueDetail(ReconciliationIssue issue, List<ReconciliationEvidence> evidence, List<ReconciliationAudit> audit) {}
