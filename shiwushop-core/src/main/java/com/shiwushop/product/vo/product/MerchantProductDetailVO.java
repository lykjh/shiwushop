package com.shiwushop.product.vo.product;

import com.shiwushop.product.vo.sku.MerchantSkuVO;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class MerchantProductDetailVO {
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
    /** 展示说明。 */
    private String description;
    /** 商品封面媒体 ID。 */
    private Long coverMediaId;
    /** 商品状态，取值见 ProductStatus。 */
    private String status;
    /** 提交审核时使用的商品资料修订号。 */
    private Long revision;
    /** 图片媒体 ID 列表。 */
    private List<Long> imageMediaIds;
    /** 商品规格选项列表。 */
    private List<ProductOptionVO> options;
    /** 商品的 SKU 列表。 */
    private List<MerchantSkuVO> skus;
    /** 仅用于展示、不参与 SKU 组合的属性列表。 */
    private List<DisplayAttributeVO> displayAttributes;
}
