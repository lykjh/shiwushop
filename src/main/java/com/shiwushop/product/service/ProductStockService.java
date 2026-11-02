package com.shiwushop.product.service;

import java.util.Collection;

/** 库存、流水和销量由商品模块维护，加入下单、取消或退款的现有事务。 */
public interface ProductStockService {
    void deductForOrder(Long skuId, Long orderItemId, int quantity, int balance, String operationKey);
    void returnCancelledOrder(Long skuId, Long orderItemId, int quantity, String operationKey);
    void returnRefund(Long skuId, Long orderItemId, int quantity, String operationKey);
    void recordSale(Long productId, int quantity);
    void refreshProducts(Collection<Long> productIds);
}
