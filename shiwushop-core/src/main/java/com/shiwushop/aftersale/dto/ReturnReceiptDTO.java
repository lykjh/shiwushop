package com.shiwushop.aftersale.dto;

import jakarta.validation.constraints.NotNull;

public record ReturnReceiptDTO(
    /** 收到的退货是否允许回补库存。 */
    @NotNull Boolean restockable
) {
}
