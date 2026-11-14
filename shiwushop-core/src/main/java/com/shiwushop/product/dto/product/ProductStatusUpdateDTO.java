package com.shiwushop.product.dto.product;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ProductStatusUpdateDTO {
    /** 商品状态，取值见 ProductStatus。 */
    @NotNull
    @Pattern(regexp = "ON_SALE|OFF_SALE")
    private String status;
}
