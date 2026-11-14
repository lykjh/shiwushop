package com.shiwushop.product.dto.review;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

public record ReviewReportDTO(
    /** 举报评价的原因。 */
    @NotBlank @CodePointLength(max = 500) String reason
) {
    public ReviewReportDTO {
        if (reason != null)
            reason = reason.strip();
    }
}
