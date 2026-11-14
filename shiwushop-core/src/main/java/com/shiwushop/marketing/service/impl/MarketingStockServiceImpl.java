package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.ActivityContentHash;
import com.shiwushop.marketing.dto.StockTransfer;
import com.shiwushop.marketing.mapper.MarketingActivityMapper;
import com.shiwushop.marketing.mapper.MarketingStockMapper;
import com.shiwushop.marketing.service.MarketingStockService;
import com.shiwushop.marketing.vo.StockPoolVO;
import com.shiwushop.marketing.vo.StockEntryPage;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.product.service.ProductStockService;
import com.shiwushop.product.service.TradingProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.HexFormat;

/** 店主划拨普通与活动库存；两侧库存和流水随同一事务提交。 */
@Service
@RequiredArgsConstructor
public class MarketingStockServiceImpl implements MarketingStockService {
    private final MarketingStockMapper mapper;
    private final MarketingActivityMapper activities;
    private final AccountSecurity security;
    private final MerchantShopAccess access;
    private final TradingProductService products;
    private final ProductStockService normalStock;
    private final MarketingSettings settings;
    private final ActivityContentHash hash;
    private final Clock businessClock;

    private void readAccess(Long actor, Long shop) {
        if (shop == null)
            security.requireAdmin(actor);
        else
            access.requireOwned(actor, shop, "店铺不存在");
        if (!settings.enabled())
            throw new BusinessException(404, "营销功能尚未启用");
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockPoolVO> pools(Long actor, Long shop, Long activityId) {
        readAccess(actor, shop);
        if (mapper.scopeCount(activityId, shop) == 0)
            throw new BusinessException(404, "活动不存在");
        return mapper.pools(activityId, shop).stream()
                .map(p -> new StockPoolVO(p.activitySkuId(), p.id(), p.skuId(), p.allocatedQty(),
                        p.availableQty(), p.heldQty(), p.consumedQty(), p.returnedToNormalQty()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StockEntryPage entries(Long actor, Long shop, Long activitySkuId, Long afterId, int size) {
        readAccess(actor, shop);
        var scope = mapper.scope(activitySkuId);
        var sku = scope == null ? null : products.findSku(scope.skuId());
        var product = sku == null ? null : products.findProduct(sku.getProductId());
        if (product == null || (shop != null && !shop.equals(product.getShopId())))
            throw new BusinessException(404, "活动商品不存在");
        var rows = mapper.entries(activitySkuId, afterId, size + 1);
        boolean more = rows.size() > size;
        var page = rows.stream().limit(size)
                .map(e -> new StockEntryPage.StockEntry(e.id(), e.reason(), e.allocatedDelta(), e.availableDelta(),
                        e.heldDelta(), e.consumedDelta(), e.returnedDelta(), e.orderItemId(), e.participationId(),
                        e.createdAt().atOffset(ZoneOffset.ofHours(8))))
                .toList();
        return new StockEntryPage(page, more, more ? page.getLast().id() : null);
    }

    @Override
    @Transactional
    public StockPoolVO.Transfer transfer(Long actor, Long shop, Long activitySkuId, String key,
                                         StockTransfer request) {
        if (!settings.enabled())
            throw new BusinessException(404, "营销功能尚未启用");
        var scope = mapper.scope(activitySkuId);
        if (scope == null)
            throw new BusinessException(404, "活动商品不存在");
        access.requireOwnedForUpdate(actor, shop, "店铺不存在");
        var sku = products.lockSku(scope.skuId());
        var product = sku == null ? null : products.findProduct(sku.getProductId());
        if (product == null || !shop.equals(product.getShopId()))
            throw new BusinessException(404, "活动商品不存在");
        var locked = mapper.lockScope(activitySkuId);
        if (locked == null || !scope.equals(locked))
            throw conflict("ACTIVITY_CHANGED", "活动商品已变化");

        boolean intoActivity = request.direction().equals("IN");
        String operationKey = HexFormat.of().formatHex(hash.hash(List.of(actor, "STOCK_TRANSFER", activitySkuId, key)));
        var previous = mapper.transferEntry(operationKey);
        if (intoActivity && previous == null
                && !Set.of("DRAFT", "PENDING_REVIEW", "APPROVED", "RUNNING", "PAUSED")
                .contains(scope.status()))
            throw conflict("INVALID_STATE", "活动已不可备货");
        if (intoActivity && previous == null
                && products.unavailableReason(sku, product, request.quantity()) != null)
            throw conflict("PRODUCT_UNAVAILABLE", "商品当前不可备货");

        int delta = intoActivity ? -request.quantity() : request.quantity();
        String machineReason = intoActivity ? "MARKETING_ALLOCATE" : "MARKETING_RELEASE";
        var movement = normalStock.moveNormalForMarketing(actor, scope.skuId(), activitySkuId, delta,
                operationKey, machineReason);

        if (intoActivity)
            mapper.ensurePool(activitySkuId);
        var pool = mapper.lockBalance(activitySkuId);
        if (pool == null)
            throw conflict("ACTIVITY_STOCK_NOT_ALLOCATED", "活动库存尚未划拨");
        int allocated = intoActivity ? request.quantity() : 0;
        int available = intoActivity ? request.quantity() : -request.quantity();
        int returned = intoActivity ? 0 : request.quantity();
        if (previous != null) {
            if (!movement.replayed() || !pool.id().equals(previous.poolId())
                    || !movement.movementId().equals(previous.normalMovementId())
                    || !actor.equals(previous.actorUserId()) || !machineReason.equals(previous.reason())
                    || !request.reason().equals(previous.operationReason())
                    || previous.allocatedDelta() != allocated || previous.availableDelta() != available
                    || previous.returnedDelta() != returned)
                throw conflict("IDEMPOTENCY_CONFLICT", "同一库存操作键对应不同请求");
            return new StockPoolVO.Transfer(view(pool, scope.skuId()), previous.id());
        }
        if (movement.replayed())
            throw conflict("LEDGER_MISMATCH", "普通与活动库存流水不一致");
        if (mapper.transfer(pool.id(), allocated, available, returned) != 1)
            throw conflict("ACTIVITY_STOCK_NOT_ENOUGH", "活动可用库存不足");
        mapper.recordTransfer(pool.id(), operationKey, machineReason, allocated, available, returned,
                movement.movementId(), actor, request.reason());
        var entry = mapper.transferEntry(operationKey);
        activities.audit(actor, scope.activityId(), "MARKETING_STOCK_TRANSFER",
                "stockEntryId=" + entry.id() + "; marketing_stock_entries.operation_reason; delta=" + delta,
                LocalDateTime.now(businessClock).truncatedTo(ChronoUnit.MILLIS));
        return new StockPoolVO.Transfer(view(mapper.lockBalance(activitySkuId), scope.skuId()), entry.id());
    }

    private static StockPoolVO view(MarketingStockMapper.Balance pool, long skuId) {
        return new StockPoolVO(pool.activitySkuId(), pool.id(), skuId, pool.allocatedQty(), pool.availableQty(),
                pool.heldQty(), pool.consumedQty(), pool.returnedToNormalQty());
    }

    private static BusinessException conflict(String reason, String message) {
        return new BusinessException(409, message, Map.of("reason", reason), null);
    }
}
