package com.shiwushop.merchant.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.merchant.entity.staff.ShopStaffGrant;
import com.shiwushop.merchant.mapper.shop.ShopMapper;
import com.shiwushop.merchant.mapper.staff.ShopStaffGrantMapper;
import com.shiwushop.merchant.service.ShopStaffService;
import com.shiwushop.merchant.vo.staff.ShopStaffVO;
import com.shiwushop.user.service.AccountAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ShopStaffServiceImpl implements ShopStaffService {
    /** 店主可授予员工的权限范围：商品目录、库存和履约。 */
    private static final Set<String> ALLOWED = Set.of("CATALOG", "INVENTORY", "FULFILLMENT");
    private final ShopMapper shops;
    private final ShopStaffGrantMapper grants;
    private final AccountAccess accounts;

    /** 店主先锁店再更新授权，撤权和员工业务写入形成确定先后。 */
    @Override
    @Transactional
    public ShopStaffVO grant(Long ownerId, Long shopId, Long userId, Set<String> roles) {
        ownedForUpdate(ownerId, shopId);
        accounts.requireActive(userId);
        if (ownerId.equals(userId) || roles == null || roles.isEmpty() || !ALLOWED.containsAll(roles))
            throw new BusinessException(400, "员工岗位无效");
        ShopStaffGrant grant = grants.selectForUpdate(shopId, userId);
        if (grant == null) {
            grant = new ShopStaffGrant();
            grant.setShopId(shopId);
            grant.setUserId(userId);
            grant.setVersion(0L);
        } else if ("ACTIVE".equals(grant.getStatus()) && roles.equals(roles(grant))) {
            return view(grant);
        }
        grant.setCanCatalog(roles.contains("CATALOG") ? 1 : 0);
        grant.setCanInventory(roles.contains("INVENTORY") ? 1 : 0);
        grant.setCanFulfillment(roles.contains("FULFILLMENT") ? 1 : 0);
        grant.setGrantedByUserId(ownerId);
        if (grant.getStatus() == null) {
            if (grants.insert(grant) != 1)
                throw new IllegalStateException("员工授权写入失败");
            grant.setStatus("ACTIVE");
        } else {
            if (grants.grant(grant) != 1)
                throw new IllegalStateException("员工授权更新失败");
            grant.setVersion(grant.getVersion() + 1);
            grant.setStatus("ACTIVE");
        }
        BusinessEventLog.committed("merchant", "staff_granted", "shop", shopId);
        return view(grant);
    }

    /** 已撤权重复请求幂等；实际撤权与授权检查共享店铺行锁。 */
    @Override
    @Transactional
    public ShopStaffVO revoke(Long ownerId, Long shopId, Long userId) {
        ownedForUpdate(ownerId, shopId);
        ShopStaffGrant grant = grants.selectForUpdate(shopId, userId);
        if (grant == null)
            throw new BusinessException(404, "员工授权不存在");
        if ("ACTIVE".equals(grant.getStatus())) {
            if (grants.revoke(shopId, userId, ownerId) != 1)
                throw new IllegalStateException("员工撤权失败");
            grant.setStatus("REVOKED");
            grant.setVersion(grant.getVersion() + 1);
            grant.setRevokedByUserId(ownerId);
            BusinessEventLog.committed("merchant", "staff_revoked", "shop", shopId);
        }
        return view(grant);
    }

    @Override
    public List<ShopStaffVO> list(Long ownerId, Long shopId) {
        Shop shop = shops.selectById(shopId);
        if (shop == null || !ownerId.equals(shop.getOwnerUserId()))
            throw new BusinessException(404, "店铺不存在");
        accounts.requireMerchant(ownerId);
        return grants.list(shopId).stream().map(this::view).toList();
    }

    private void ownedForUpdate(Long ownerId, Long shopId) {
        Shop shop = shops.selectForUpdate(shopId);
        if (shop == null || !ownerId.equals(shop.getOwnerUserId()))
            throw new BusinessException(404, "店铺不存在");
        accounts.requireMerchant(ownerId);
    }

    private Set<String> roles(ShopStaffGrant grant) {
        Set<String> result = new HashSet<>();
        if (grant.getCanCatalog() == 1) result.add("CATALOG");
        if (grant.getCanInventory() == 1) result.add("INVENTORY");
        if (grant.getCanFulfillment() == 1) result.add("FULFILLMENT");
        return Set.copyOf(result);
    }

    private ShopStaffVO view(ShopStaffGrant grant) {
        return new ShopStaffVO(grant.getShopId(), grant.getUserId(), roles(grant), grant.getStatus(),
                grant.getVersion(), grant.getGrantedByUserId(), grant.getRevokedByUserId());
    }
}
