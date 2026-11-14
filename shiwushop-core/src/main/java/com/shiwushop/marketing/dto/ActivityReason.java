package com.shiwushop.marketing.dto;

import jakarta.validation.constraints.*;

public record ActivityReason(
    /** 本次业务操作的原因。 */
    @NotBlank @Size(max = 500) String reason
) {
}
