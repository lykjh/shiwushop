package com.shiwushop.trade.vo.cart;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CartItemVO {
    /** 购物车明细 ID。 */
    private Long id;
    /** 商品规格（SKU）ID。 */
    private Long skuId;
    /** 商品（SPU）ID。 */
    private Long productId;
    /** 商品名称。 */
    private String productName;
    /** 该 SKU 的规格值展示列表。 */
    private List<String> optionValues;
    /** 商品单价，单位元。 */
    private BigDecimal price;
    /** 商品件数。 */
    private Integer quantity;
    /** 当前是否可购买。 */
    private Boolean available;
    /** 当前不可购买的原因。 */
    private String unavailableReason;
}
