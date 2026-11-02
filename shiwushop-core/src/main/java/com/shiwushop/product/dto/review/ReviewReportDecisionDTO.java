package com.shiwushop.product.dto.review;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

public record ReviewReportDecisionDTO(@NotBlank @Pattern(regexp = "KEEP|HIDE") String decision,
                                      @NotBlank @CodePointLength(max = 500) String reason) {
    public ReviewReportDecisionDTO { if (reason != null) reason = reason.strip(); }
}
