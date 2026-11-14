package com.shiwushop.product.vo.sku;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
public class MerchantSkuVO {
    /** 当前记录 ID。 */
    private Long id;
    /** 商品（SPU）ID。 */
    private Long productId;
    /** 组成 SKU 的规格选项值 ID 列表。 */
    private List<Long> optionValueIds;
    /** 商品单价，单位元。 */
    private BigDecimal price;
    /** 普通可售库存件数。 */
    private Integer stock;
}
