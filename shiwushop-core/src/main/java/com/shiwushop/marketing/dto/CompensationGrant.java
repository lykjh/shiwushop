package com.shiwushop.marketing.dto;

import jakarta.validation.constraints.*;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CompensationGrant(
    /** 所属用户 ID。 */
    @NotNull @Positive @JsonDeserialize(using = JsonLongValue.class) Long userId,
    /** 本次发券张数。 */
    @NotNull @Min(1) @Max(100) @JsonDeserialize(using = JsonIntegerValue.class) Integer count,
    /** 本次业务操作的原因。 */
    @NotBlank @Size(max = 500) String reason,
    /** 操作凭据引用。 */
    @NotBlank @Size(max = 500) String evidenceRef
) {
}
