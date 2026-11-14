package com.shiwushop.merchant.service;

import com.shiwushop.merchant.entity.shop.Shop;

/** 商家归属检查；写入版本持有店铺锁直到调用方事务结束。 */
public interface MerchantShopAccess {
    Shop requireOwned(Long userId, Long shopId, String notFoundMessage);

    Shop requireOwnedForUpdate(Long userId, Long shopId, String notFoundMessage);

    /** 店主或获岗位授权员工读取本店业务；不授予资金和私密资料。 */
    Shop requireRole(Long userId, Long shopId, String role, String notFoundMessage);

    /** 写操作按店铺再授权行加锁，并在锁后核对当前身份。 */
    Shop requireRoleForUpdate(Long userId, Long shopId, String role, String notFoundMessage);
}
