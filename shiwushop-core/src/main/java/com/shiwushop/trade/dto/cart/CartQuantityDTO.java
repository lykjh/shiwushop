package com.shiwushop.trade.dto.cart;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CartQuantityDTO {
    /** 商品件数。 */
    @NotNull
    @Min(1)
    @Max(10000)
    private Integer quantity;
}
