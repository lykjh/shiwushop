package com.shiwushop.product.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.product.mapper.product.InventoryMovementMapper;
import com.shiwushop.product.mapper.product.ProductMapper;
import com.shiwushop.product.mapper.product.SkuMapper;
import com.shiwushop.product.service.ProductStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;

/** 交易和售后只提交业务动作；库存与流水一起成功或随调用方事务回滚。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ProductStockServiceImpl implements ProductStockService {
    private final SkuMapper skus;
    private final ProductMapper products;
    private final InventoryMovementMapper movements;
    private final OutboxEventMapper outbox;

    @Override
    public void deductForOrder(Long skuId, Long itemId, int quantity, int balance, String key) {
        if (skus.decreaseStock(skuId, quantity) != 1)
            throw new BusinessException(409, "库存不足", Map.of("reason", "OUT_OF_STOCK", "skuId", skuId), null);
        movements.insertOrderDeduct(skuId, itemId, -quantity, balance, key);
    }

    @Override
    public void returnCancelledOrder(Long skuId, Long itemId, int quantity, String key) {
        if (skus.increaseStock(skuId, quantity) != 1) throw new BusinessException(409, "库存归还失败");
        movements.insertOrderCancelReturn(skuId, itemId, quantity, skus.selectStock(skuId), key);
    }

    @Override
    public void returnRefund(Long skuId, Long itemId, int quantity, String key) {
        if (skus.increaseStock(skuId, quantity) != 1) throw new BusinessException(409, "库存返还失败");
        movements.insertRefundReturn(skuId, itemId, quantity, skus.selectStock(skuId), key);
    }

    @Override
    public void recordSale(Long productId, int quantity) { products.increaseSoldCount(productId, quantity); }

    @Override
    public void refreshProducts(Collection<Long> productIds) {
        productIds.stream().distinct().forEach(id -> {
            if (outbox.insertSearchProductRefresh(id) != 1) throw new IllegalStateException("商品搜索事件写入失败");
        });
    }
}
