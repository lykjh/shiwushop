package com.shiwushop.catalog.auth;

import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.common.auth.SessionUser;

import com.shiwushop.common.BusinessException;


import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MerchantShopAccess {
    private final ShopMapper shopMapper;
    private final AccountAccess accountAccess;

    /** 只读确认店铺归属与商家角色；不存在或不属于当前商家时以 404 隐藏店铺。 */
    public Shop requireOwned(Long userId, Long shopId, String notFoundMessage) {
        return requireOwned(userId, shopId, notFoundMessage, false);
    }

    /** 写入前锁定店铺并确认归属，使转店与商家修改不能同时通过旧归属校验。 */
    public Shop requireOwnedForUpdate(Long userId, Long shopId, String notFoundMessage) {
        return requireOwned(userId, shopId, notFoundMessage, true);
    }

    /** 两种归属检查的共同实现；由 lock 决定是否使用数据库行锁。 */
    private Shop requireOwned(Long userId, Long shopId, String notFoundMessage, boolean lock) {
        SessionUser.requireId(userId);
        Shop shop = lock ? shopMapper.selectForUpdate(shopId) : shopMapper.selectById(shopId);
        if (shop == null || !userId.equals(shop.getOwnerUserId())) {
            throw new BusinessException(404, notFoundMessage);
        }
        accountAccess.requireMerchant(userId);
        return shop;
    }
}
