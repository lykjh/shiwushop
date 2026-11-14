package com.shiwushop.marketing.dto;

import jakarta.validation.constraints.*;

public record ActivityDecision(
    /** APPROVE 批准活动，REJECT 驳回活动。 */
    @NotNull @Pattern(regexp = "APPROVE|REJECT") String decision,
    /** 本次业务操作的原因。 */
    @NotBlank @Size(max = 500) String reason
) {
}
