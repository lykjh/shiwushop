package com.shiwushop.aftersale.dto.timeout;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CloseAfterSaleTimeoutDTO(
    /** 人工处理所依据的凭据。 */
    @NotBlank @Size(max = 1000) String evidence,
    /** 人工处理结论说明。 */
    @NotBlank @Size(max = 1000) String conclusion
) {
}
