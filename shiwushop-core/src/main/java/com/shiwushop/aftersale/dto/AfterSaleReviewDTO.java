package com.shiwushop.aftersale.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AfterSaleReviewDTO(@NotNull Boolean approved, @Size(max = 500) String reason) {
    public AfterSaleReviewDTO { if (reason != null) reason = reason.strip(); }

    /** 审核通过允许不填原因，拒绝申请必须向买家说明原因。 */
    @AssertTrue(message = "拒绝售后时必须填写原因")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isReasonProvidedWhenRejected() {
        return !Boolean.FALSE.equals(approved) || reason != null && !reason.isEmpty();
    }
}
