package com.shiwushop.merchant.service;

/** 店铺列表需要分类可见性；由商品模块实现，避免商家模块反向依赖商品实现。 */
public interface ShopCategoryAccess {
    void requireVisible(Long categoryId);
}
