package com.shiwushop.dto.cart;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class CartAddDTO {
    @NotNull
    @Positive
    private Long skuId;

    @NotNull
    @Min(1)
    @Max(10000)
    private Integer quantity;
}
