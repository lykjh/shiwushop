package com.shiwushop.payment.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

public record ReconciliationEvidenceDTO(
    /** 账单侧核对凭据。 */
    @NotBlank @CodePointLength(max = 2000) String billEvidence,
    /** 本地订单及支付记录核对凭据。 */
    @NotBlank @CodePointLength(max = 2000) String localEvidence,
    /** 支付渠道查询结果凭据。 */
    @NotBlank @CodePointLength(max = 2000) String channelQueryEvidence
) {
    public ReconciliationEvidenceDTO {
        if (billEvidence != null)
            billEvidence = billEvidence.strip();
        if (localEvidence != null)
            localEvidence = localEvidence.strip();
        if (channelQueryEvidence != null)
            channelQueryEvidence = channelQueryEvidence.strip();
    }
}
