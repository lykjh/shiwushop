package com.shiwushop.product.vo.product;

import com.shiwushop.merchant.entity.shop.Shop;

import com.shiwushop.product.vo.brand.BrandPublicVO;
import com.shiwushop.product.vo.category.CategoryPathItemVO;
import com.shiwushop.product.vo.sku.PublicSkuVO;

import lombok.Data;

import java.util.List;

@Data
public class PublicProductDetailVO {

    /** 当前记录 ID。 */
    private Long id;
    /** 名称。 */
    private String name;
    /** 展示说明。 */
    private String description;
    /** 商品封面媒体 ID。 */
    private Long coverMediaId;
    /** 图片媒体 ID 列表。 */
    private List<Long> imageMediaIds;
    /** 从上级到当前分类的路径。 */
    private List<CategoryPathItemVO> categoryPath;
    /** 品牌展示信息。 */
    private BrandPublicVO brand;
    /** 已统计的销量件数。 */
    private Long soldCount;
    /** 店铺展示信息。 */
    private PublicProductVO.Shop shop;
    /** 商品规格选项列表。 */
    private List<ProductOptionVO> options;
    /** 商品的 SKU 列表。 */
    private List<PublicSkuVO> skus;
    /** 仅用于展示、不参与 SKU 组合的属性列表。 */
    private List<DisplayAttributeVO> displayAttributes;
}
