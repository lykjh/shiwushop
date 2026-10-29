package com.shiwushop.catalog.dto.sku;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class MerchantSkuCreateDTO {
    @NotEmpty
    private List<@NotNull @Positive Long> optionValueIds;

    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal price;

    @NotNull
    @Min(0)
    private Integer stock;
}
