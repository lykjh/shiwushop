package com.shiwushop.dto.cart;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CheckoutDTO {
    public static final int MAX_CART_ITEMS = 100;

    @NotEmpty @Size(max = MAX_CART_ITEMS)
    private List<@NotNull @Positive Long> cartItemIds;
    @NotNull @Positive
    private Long addressId;
    @NotNull @DecimalMin("0.01")
    private BigDecimal confirmedTotal;
    @NotBlank @Size(max = 64)
    private String idempotencyKey;
}
