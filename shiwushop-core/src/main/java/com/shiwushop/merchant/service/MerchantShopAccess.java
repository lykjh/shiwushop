package com.shiwushop.merchant.service;

import com.shiwushop.merchant.entity.shop.Shop;

/** 商家归属检查；写入版本持有店铺锁直到调用方事务结束。 */
public interface MerchantShopAccess {
    Shop requireOwned(Long userId, Long shopId, String notFoundMessage);
    Shop requireOwnedForUpdate(Long userId, Long shopId, String notFoundMessage);
}
