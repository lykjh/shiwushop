package com.shiwushop.product.dto.review;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

public record ReviewReportDecisionDTO(
    /** KEEP 保留评价，HIDE 隐藏评价。 */
    @NotBlank @Pattern(regexp = "KEEP|HIDE") String decision,
    /** 本次业务操作的原因。 */
    @NotBlank @CodePointLength(max = 500) String reason
) {
    public ReviewReportDecisionDTO {
        if (reason != null)
            reason = reason.strip();
    }
}
