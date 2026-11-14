package com.shiwushop.product.vo.sku;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class PublicSkuVO {

    /** 当前记录 ID。 */
    private Long id;
    /** 组成 SKU 的规格选项值 ID 列表。 */
    private List<Long> optionValueIds;
    /** 商品单价，单位元。 */
    private BigDecimal price;
    /** 当前是否可购买。 */
    private Boolean available;
}
