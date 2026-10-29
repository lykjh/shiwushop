package com.shiwushop.catalog.vo.product;

import com.shiwushop.catalog.entity.shop.Shop;

import com.shiwushop.catalog.vo.brand.BrandPublicVO;
import com.shiwushop.catalog.vo.category.CategoryPathItemVO;
import com.shiwushop.catalog.vo.sku.PublicSkuVO;

import lombok.Data;

import java.util.List;

@Data
public class PublicProductDetailVO {

    private Long id;
    private String name;
    private String description;
    private Long coverMediaId;
    private List<Long> imageMediaIds;
    private List<CategoryPathItemVO> categoryPath;
    private BrandPublicVO brand;
    private Long soldCount;
    private PublicProductVO.Shop shop;
    private List<ProductOptionVO> options;
    private List<PublicSkuVO> skus;
}
