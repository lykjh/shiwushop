package com.shiwushop.product.vo.product;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MerchantProductVO {
    /** 当前记录 ID。 */
    private Long id;
    /** 所属店铺 ID。 */
    private Long shopId;
    /** 分类 ID。 */
    private Long categoryId;
    /** 品牌 ID。 */
    private Long brandId;
    /** 名称。 */
    private String name;
    /** 商品状态，取值见 ProductStatus。 */
    private String status;
}
