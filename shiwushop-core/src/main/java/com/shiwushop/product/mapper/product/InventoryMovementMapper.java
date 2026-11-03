package com.shiwushop.product.mapper.product;

import com.shiwushop.product.dto.inventory.InventoryMovementQuery;
import com.shiwushop.product.vo.inventory.InventoryMovementVO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

public interface InventoryMovementMapper {
    @Select("""
            <script>
            SELECT id, reason, delta, balance_after, order_item_id, created_at
            FROM inventory_movements WHERE sku_id = #{skuId}
            <if test="query.reason != null">AND reason = #{query.reason}</if>
            <if test="query.createdFrom != null">AND created_at &gt;= #{query.createdFrom}</if>
            <if test="query.createdTo != null">AND created_at &lt; #{query.createdTo}</if>
            <if test="query.afterId != null">AND id &lt; #{query.afterId}</if>
            ORDER BY id DESC LIMIT #{limit}
            </script>
            """)
    List<InventoryMovementVO> selectPage(@Param("skuId") Long skuId,
            @Param("query") InventoryMovementQuery query, @Param("limit") int limit);
    @Insert("""
            INSERT INTO inventory_movements (sku_id, reason, delta, balance_after, operation_key)
            VALUES (#{skuId}, 'MERCHANT_ADJUST', #{delta}, #{balanceAfter}, #{operationKey})
            """)
    int insertMerchantAdjust(@Param("skuId") Long skuId, @Param("delta") int delta,
                             @Param("balanceAfter") int balanceAfter,
                             @Param("operationKey") String operationKey);

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
