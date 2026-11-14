package com.shiwushop.payment.dto;

import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;

public record ReconciliationResolveDTO(
    /** 关联凭据记录 ID。 */
    @NotNull @Positive Long evidenceId,
    /** 人工处理结论类型。 */
    @NotBlank @Pattern(regexp = "CHANNEL_ONLY|LOCAL_ONLY|AMOUNT_MISMATCH|REFUND_STATUS_MISMATCH|NO_ACTION") String conclusionType,
    /** 人工处理结论说明。 */
    @NotBlank @CodePointLength(max = 1000) String conclusion,
    /** 线下人工处置凭据引用。 */
    @Pattern(regexp = ".+") @CodePointLength(max = 200) String manualHandlingReference
) {
    public ReconciliationResolveDTO {
        if (conclusionType != null)
            conclusionType = conclusionType.strip();
        if (conclusion != null)
            conclusion = conclusion.strip();
        if (manualHandlingReference != null)
            manualHandlingReference = manualHandlingReference.strip();
    }
}
