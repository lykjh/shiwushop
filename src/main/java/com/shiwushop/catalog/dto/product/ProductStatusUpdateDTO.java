package com.shiwushop.catalog.dto.product;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ProductStatusUpdateDTO {
    @NotNull
    @Pattern(regexp = "ON_SALE|OFF_SALE")
    private String status;
}
