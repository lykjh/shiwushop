package com.shiwushop.common.auth;

import com.shiwushop.common.BusinessException;


import com.shiwushop.entity.shop.Shop;
import com.shiwushop.mapper.shop.ShopMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MerchantShopAccess {
    private final ShopMapper shopMapper;
    private final AccountAccess accountAccess;

    public Shop requireOwned(Long userId, Long shopId, String notFoundMessage) {
        SessionUser.requireId(userId);
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null || !userId.equals(shop.getOwnerUserId())) {
            throw new BusinessException(404, notFoundMessage);
        }
        accountAccess.requireMerchant(userId);
        return shop;
    }
}
