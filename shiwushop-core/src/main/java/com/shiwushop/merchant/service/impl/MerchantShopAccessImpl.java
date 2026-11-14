package com.shiwushop.merchant.service.impl;

import com.shiwushop.user.service.AccountAccess;

import com.shiwushop.common.BusinessException;

import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.merchant.mapper.shop.ShopMapper;
import com.shiwushop.merchant.entity.staff.ShopStaffGrant;
import com.shiwushop.merchant.mapper.staff.ShopStaffGrantMapper;
import lombok.RequiredArgsConstructor;
import com.shiwushop.merchant.service.MerchantShopAccess;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MerchantShopAccessImpl implements MerchantShopAccess {
    private final ShopMapper shopMapper;
    private final AccountAccess accountAccess;
    private final ShopStaffGrantMapper staffGrants;

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

        Shop shop = lock ? shopMapper.selectForUpdate(shopId) : shopMapper.selectById(shopId);
        if (shop == null || !userId.equals(shop.getOwnerUserId())) {
            throw new BusinessException(404, notFoundMessage);
        }
        accountAccess.requireMerchant(userId);
        return shop;
    }

    @Override
    public Shop requireRole(Long userId, Long shopId, String role, String notFoundMessage) {
        return requireRole(userId, shopId, role, notFoundMessage, false);
    }

    @Override
    public Shop requireRoleForUpdate(Long userId, Long shopId, String role, String notFoundMessage) {
        return requireRole(userId, shopId, role, notFoundMessage, true);
    }

    private Shop requireRole(Long userId, Long shopId, String role, String notFoundMessage, boolean lock) {
        Shop shop = lock ? shopMapper.selectForUpdate(shopId) : shopMapper.selectById(shopId);
        if (shop == null)
            throw new BusinessException(404, notFoundMessage);
        if (userId.equals(shop.getOwnerUserId())) {
            accountAccess.requireMerchant(userId);
            return shop;
        }
        accountAccess.requireActive(userId);
        ShopStaffGrant grant = lock ? staffGrants.selectForUpdate(shopId, userId) : staffGrants.select(shopId, userId);
        if (grant == null || !"ACTIVE".equals(grant.getStatus()) || !hasRole(grant, role))
            throw new BusinessException(404, notFoundMessage);
        return shop;
    }

    private boolean hasRole(ShopStaffGrant grant, String role) {
        return switch (role) {
            case "CATALOG" -> Integer.valueOf(1).equals(grant.getCanCatalog());
            case "INVENTORY" -> Integer.valueOf(1).equals(grant.getCanInventory());
            case "FULFILLMENT" -> Integer.valueOf(1).equals(grant.getCanFulfillment());
            default -> false;
        };
    }
}
