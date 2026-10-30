package com.shiwushop.commerce.service.impl;

import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.entity.status.ProductStatus;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.catalog.service.CategoryService;
import com.shiwushop.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 购物车展示和结算使用同一可售性规则，避免两条路径判定不一致。 */
@Component
@RequiredArgsConstructor
class CartAvailability {
    private final ShopMapper shopMapper;
    private final CategoryService categoryService;
    /** 查询店铺和分类状态后，返回该数量不可下单的原因；可购买时返回 null。 */
    String unavailableReason(Sku sku, Product product, int quantity) {
        if (sku == null || product == null) return "商品不存在";
        if (product.getStatus() != ProductStatus.ON_SALE) return "商品已下架";
        Shop shop = product.getShopId() == null ? null : shopMapper.selectById(product.getShopId());
        if (shop == null || !Integer.valueOf(1).equals(shop.getEnabled())) return "店铺已暂停营业";
        boolean visible;
        try {
            categoryService.detail(product.getCategoryId());
            visible = true;
        } catch (BusinessException e) {
            visible = false;
        }
        return unavailableReason(sku, product, quantity, shop, visible);
    }

    /** 复用调用方已装载的数据判定可售性，避免列表逐项重复查询。 */
    String unavailableReason(Sku sku, Product product, int quantity, Shop shop, boolean categoryVisible) {
        if (sku == null || product == null) return "商品不存在";
        if (product.getStatus() != ProductStatus.ON_SALE) return "商品已下架";
        if (shop == null || !Integer.valueOf(1).equals(shop.getEnabled())) return "店铺已暂停营业";
        if (!categoryVisible) return "分类已隐藏";
        if (sku.getStock() == null || sku.getStock() < quantity) return "库存不足";
        return null;
    }
}
