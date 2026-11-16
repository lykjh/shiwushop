package com.shiwushop.product.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.product.Sku;
import com.shiwushop.product.entity.inventory.MarketingInventoryMovement;
import com.shiwushop.product.vo.stock.MarketingStockMovement;
import com.shiwushop.product.mapper.product.InventoryMovementMapper;
import com.shiwushop.product.mapper.product.ProductMapper;
import com.shiwushop.product.mapper.product.SkuMapper;
import com.shiwushop.product.service.ProductStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** 交易和售后只提交业务动作；库存与流水一起成功或随调用方事务回滚。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ProductStockServiceImpl implements ProductStockService {
    private final SkuMapper skus;
    private final ProductMapper products;
    private final InventoryMovementMapper movements;
    private final MerchantShopAccess shops;

    /** 活动划拨只改普通池；调用方在同一事务内改活动池并写对应流水。 */
    @Override
    public MarketingStockMovement moveNormalForMarketing(Long actorUserId, Long skuId, Long activitySkuId,
                                                          int normalDelta, String operationKey, String operationReason) {
        if (actorUserId == null || skuId == null || activitySkuId == null
                || actorUserId <= 0 || skuId <= 0 || activitySkuId <= 0
                || normalDelta == 0
                || operationKey == null || !operationKey.matches("[!-~]{1,64}"))
            throw new BusinessException(400, "活动划拨参数无效");
        String reason = normalDelta < 0 ? "MARKETING_ALLOCATE" : "MARKETING_RELEASE";
        if (!reason.equals(operationReason))
            throw new BusinessException(400, "活动划拨方向与原因不一致");

        Sku initialSku = skus.selectById(skuId);
        Product initialProduct = initialSku == null ? null : products.selectById(initialSku.getProductId());
        if (initialProduct == null)
            throw new BusinessException(404, "SKU不存在");
        shops.requireRoleForUpdate(actorUserId, initialProduct.getShopId(), "INVENTORY", "店铺不存在");
        Sku sku = skus.selectForUpdate(skuId);
        Product product = sku == null ? null : products.selectById(sku.getProductId());
        if (product == null || !initialProduct.getShopId().equals(product.getShopId())
                || skus.selectMarketingBinding(activitySkuId, skuId, product.getShopId()) == null)
            throw new BusinessException(404, "活动SKU与店铺不匹配");

        String scopedKey = "MARKETING:" + operationKey;
        MarketingInventoryMovement prior = movements.selectMarketingByKey(scopedKey);
        if (prior != null) {
            if (!skuId.equals(prior.getSkuId()) || !activitySkuId.equals(prior.getActivitySkuId())
                    || !actorUserId.equals(prior.getActorUserId()) || normalDelta != prior.getDelta()
                    || !reason.equals(prior.getReason()))
                throw new BusinessException(409, "活动划拨操作键与原请求冲突");
            return new MarketingStockMovement(prior.getId(), prior.getBalanceAfter(), prior.getDelta(), true);
        }
        if (normalDelta < 0 && !"ACTIVE".equals(sku.getStatus()))
            throw new BusinessException(409, "SKU已停售");
        long after = (long) sku.getStock() + normalDelta;
        if (after < 0 || after > Integer.MAX_VALUE)
            throw new BusinessException(409, "普通库存不足或超出上限");
        int changed = normalDelta < 0 ? skus.decreaseStock(skuId, -normalDelta)
                : skus.increaseStock(skuId, normalDelta);
        if (changed != 1)
            throw new BusinessException(409, "普通库存划拨失败");
        MarketingInventoryMovement movement = new MarketingInventoryMovement();
        movement.setSkuId(skuId);
        movement.setActivitySkuId(activitySkuId);
        movement.setActorUserId(actorUserId);
        movement.setReason(reason);
        movement.setDelta(normalDelta);
        movement.setBalanceAfter((int) after);
        movement.setOperationKey(scopedKey);
        try {
            if (movements.insertMarketing(movement) != 1)
                throw new IllegalStateException("普通库存划拨流水写入失败");
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "活动划拨操作键与原请求冲突", e);
        }
        return new MarketingStockMovement(movement.getId(), (int) after, normalDelta, false);
    }

    @Override
    public void deductForOrder(Long skuId, Long itemId, int quantity, int balance, String key) {
        if (skus.decreaseStock(skuId, quantity) != 1)
            throw new BusinessException(409, "库存不足", Map.of("reason", "OUT_OF_STOCK", "skuId", skuId), null);
        movements.insertOrderDeduct(skuId, itemId, -quantity, balance, key);
    }

    @Override
    public void returnCancelledOrder(Long skuId, Long itemId, int quantity, String key) {
        if (skus.increaseStock(skuId, quantity) != 1)
            throw new BusinessException(409, "库存归还失败");
        movements.insertOrderCancelReturn(skuId, itemId, quantity, skus.selectStock(skuId), key);
    }

    @Override
    public void returnRefund(Long skuId, Long itemId, int quantity, String key) {
        if (skus.increaseStock(skuId, quantity) != 1)
            throw new BusinessException(409, "库存返还失败");
        movements.insertRefundReturn(skuId, itemId, quantity, skus.selectStock(skuId), key);
    }

    @Override
    public void recordSale(Long productId, int quantity) {
        products.increaseSoldCount(productId, quantity);
    }

}
