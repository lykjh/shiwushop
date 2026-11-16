package com.shiwushop.product.service;

import java.util.Collection;
import com.shiwushop.product.vo.stock.MarketingStockMovement;

/** 库存、流水和销量由商品模块维护，加入下单、取消或退款的现有事务。 */
public interface ProductStockService {
    void deductForOrder(Long skuId, Long orderItemId, int quantity, int balance, String operationKey);

    void returnCancelledOrder(Long skuId, Long orderItemId, int quantity, String operationKey);

    void returnRefund(Long skuId, Long orderItemId, int quantity, String operationKey);

    void recordSale(Long productId, int quantity);

    /** 已持有事务与店铺先序锁的活动编排调用；负数划出、正数划回。 */
    MarketingStockMovement moveNormalForMarketing(Long actorUserId, Long skuId, Long activitySkuId,
                                                   int normalDelta, String operationKey, String operationReason);
}
