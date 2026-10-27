package com.shiwushop.mapper.product;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface InventoryMovementMapper {
    @Insert("""
            INSERT INTO inventory_movements (sku_id, order_item_id, reason, delta, balance_after, operation_key)
            VALUES (#{skuId}, #{orderItemId}, 'ORDER_DEDUCT', #{delta}, #{balanceAfter}, #{operationKey})
            """)
    int insertOrderDeduct(@Param("skuId") Long skuId, @Param("orderItemId") Long orderItemId,
                          @Param("delta") int delta, @Param("balanceAfter") int balanceAfter,
                          @Param("operationKey") String operationKey);

    @Insert("""
            INSERT INTO inventory_movements (sku_id, order_item_id, reason, delta, balance_after, operation_key)
            VALUES (#{skuId}, #{orderItemId}, 'ORDER_CANCEL_RETURN', #{quantity}, #{balanceAfter}, #{operationKey})
            """)
    int insertOrderCancelReturn(@Param("skuId") Long skuId, @Param("orderItemId") Long orderItemId,
                                @Param("quantity") int quantity, @Param("balanceAfter") int balanceAfter,
                                @Param("operationKey") String operationKey);

    @Insert("""
            INSERT INTO inventory_movements (sku_id, order_item_id, reason, delta, balance_after, operation_key)
            VALUES (#{skuId}, #{orderItemId}, 'REFUND_RETURN', #{quantity}, #{balanceAfter}, #{operationKey})
            """)
    int insertRefundReturn(@Param("skuId") Long skuId, @Param("orderItemId") Long orderItemId,
                           @Param("quantity") int quantity, @Param("balanceAfter") int balanceAfter,
                           @Param("operationKey") String operationKey);
}
