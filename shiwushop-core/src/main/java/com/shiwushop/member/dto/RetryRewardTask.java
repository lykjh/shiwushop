package com.shiwushop.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RetryRewardTask(
    /** 本次业务操作的原因。 */
    @NotBlank @Size(max = 500) String reason
) {
}
