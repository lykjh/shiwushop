package com.shiwushop.payment.service;

import com.shiwushop.payment.dto.ReconciliationEvidenceDTO;
import com.shiwushop.payment.dto.ReconciliationResolveDTO;
import com.shiwushop.payment.entity.ReconciliationEvidence;
import com.shiwushop.payment.entity.ReconciliationIssue;
import com.shiwushop.payment.vo.ReconciliationIssueDetail;
import com.shiwushop.payment.vo.ReconciliationIssuePage;

/** 查询支付对账差异并处理认领、证据和结论。 */
public interface ReconciliationIssueService {
    ReconciliationIssuePage list(String status, Long afterId, int size);

    ReconciliationIssueDetail detail(Long id);

    ReconciliationIssue claim(Long id, Long actor);

    ReconciliationEvidence addEvidence(Long id, Long actor, ReconciliationEvidenceDTO dto);

    ReconciliationIssue resolve(Long id, Long actor, ReconciliationResolveDTO dto);
}
