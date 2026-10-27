package com.shiwushop.dto.cart;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DirectCheckoutDTO {
    @NotNull @Positive
    private Long skuId;
    @NotNull @Min(1) @Max(10000)
    private Integer quantity;
    @NotNull @Positive
    private Long addressId;
    @NotNull @DecimalMin("0.01")
    private BigDecimal confirmedTotal;
    @NotBlank @Size(max = 64)
    private String idempotencyKey;
}
