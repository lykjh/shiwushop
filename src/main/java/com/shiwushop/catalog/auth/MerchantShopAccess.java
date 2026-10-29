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

    public Shop requireOwned(Long userId, Long shopId, String notFoundMessage) {
        return requireOwned(userId, shopId, notFoundMessage, false);
    }

    public Shop requireOwnedForUpdate(Long userId, Long shopId, String notFoundMessage) {
        return requireOwned(userId, shopId, notFoundMessage, true);
    }

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
