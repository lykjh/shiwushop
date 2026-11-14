package com.shiwushop.user.dto.user;

import com.shiwushop.user.entity.status.AccountStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminAccountStatusDTO(
    /** 账号状态：ACTIVE 正常、DISABLED 停用。 */
    @NotNull AccountStatus status,
    /** 本次业务操作的原因。 */
    @NotBlank @Size(max = 300) String reason
) {
}
