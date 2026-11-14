package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.mapper.MarketingStockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** 在调用方已锁定参与根与SKU的事务中流转活动池，不再触碰普通库存。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MarketingStockLedger {
    private final MarketingStockMapper mapper;

    public void reserve(long scope, long participation, int quantity, String key) {
        change(scope, participation, quantity, key, "RESERVE", -quantity, quantity, 0);
    }

    public void confirm(long scope, long participation, int quantity, String key) {
        change(scope, participation, quantity, key, "COMMIT", 0, -quantity, quantity);
    }

    public void release(long scope, long participation, int quantity, String key) {
        change(scope, participation, quantity, key, "RELEASE", quantity, -quantity, 0);
    }

    public void refund(long scope, long participation, int quantity, String key) {
        change(scope, participation, quantity, key, "REFUND", quantity, 0, -quantity);
    }

    private void change(long scope, long participation, int quantity, String key, String reason,
                        int available, int held, int consumed) {
        if (quantity <= 0)
            throw new IllegalArgumentException("活动库存件数必须为正");
        var pool = mapper.lockPool(scope);
        if (pool == null)
            throw conflict("ACTIVITY_STOCK_NOT_ALLOCATED", "活动库存尚未划拨");
        var previous = mapper.entry(key);
        if (previous != null) {
            if (!previous.poolId().equals(pool.id()) || !previous.participationId().equals(participation)
                    || !previous.reason().equals(reason) || previous.availableDelta() != available
                    || previous.heldDelta() != held || previous.consumedDelta() != consumed)
                throw conflict("IDEMPOTENCY_CONFLICT", "同一库存操作键对应不同请求");
            return;
        }
        if (mapper.change(pool.id(), available, held, consumed) != 1)
            throw conflict("ACTIVITY_STOCK_NOT_ENOUGH", "活动库存不足或状态已变化");
        mapper.record(pool.id(), key, reason, available, held, consumed, participation);
    }

    private static BusinessException conflict(String reason, String message) {
        return new BusinessException(409, message, Map.of("reason", reason), null);
    }
}
