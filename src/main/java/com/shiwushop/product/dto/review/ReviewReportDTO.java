package com.shiwushop.product.dto.review;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

public record ReviewReportDTO(@NotBlank @CodePointLength(max = 500) String reason) {
    public ReviewReportDTO { if (reason != null) reason = reason.strip(); }
}
