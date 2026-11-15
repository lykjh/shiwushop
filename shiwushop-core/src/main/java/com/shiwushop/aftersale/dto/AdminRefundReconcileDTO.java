package com.shiwushop.aftersale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminRefundReconcileDTO(
        @NotNull Action action,
        @NotBlank @Size(max = 400) String reason
) {
    public enum Action { VERIFY, RETRY }
}
