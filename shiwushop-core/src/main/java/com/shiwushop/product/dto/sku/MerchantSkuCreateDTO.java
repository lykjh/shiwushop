package com.shiwushop.product.dto.sku;

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
    /** 组成 SKU 的规格选项值 ID 列表。 */
    @NotEmpty
    private List<@NotNull @Positive Long> optionValueIds;

    /** 商品单价，单位元。 */
    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal price;

    /** 普通可售库存件数。 */
    @NotNull
    @Min(0)
    private Integer stock;
}
