package com.shiwushop.trade.mapper.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.vo.order.OrderSummaryVO;
import com.shiwushop.trade.vo.order.OrderRewardSummary;
import com.shiwushop.trade.vo.order.OrderFulfillmentVO;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface OrderMapper extends BaseMapper<Order> {
    List<OrderSummaryVO> selectBuyerSlice(@Param("userId") Long userId,
                                          @Param("beforeCreatedAt") LocalDateTime beforeCreatedAt,
                                          @Param("beforeId") Long beforeId, @Param("limit") int limit);

    List<OrderSummaryVO> selectMerchantSlice(@Param("shopId") Long shopId,
                                             @Param("beforeCreatedAt") LocalDateTime beforeCreatedAt,
                                             @Param("beforeId") Long beforeId, @Param("limit") int limit);

    @MapKey("orderId")
    Map<Long, OrderRewardSummary> rewardSummaries(@Param("orderIds") List<Long> orderIds);

    @Select("""
            SELECT
              (SELECT COALESCE(SUM(quantity),0) FROM order_items WHERE order_id=#{orderId}) totalQuantity,
              (SELECT COALESCE(SUM(si.quantity),0) FROM shipment_items si
                JOIN shipments s ON s.id=si.shipment_id WHERE s.order_id=#{orderId}) shippedQuantity,
              (SELECT COALESCE(SUM(si.quantity),0) FROM shipment_items si
                JOIN shipments s ON s.id=si.shipment_id
                WHERE s.order_id=#{orderId} AND s.status='CONFIRMED') confirmedQuantity,
              (SELECT COALESCE(SUM(ai.quantity),0) FROM after_sale_items ai
                JOIN after_sale_cases c ON c.id=ai.case_id
                WHERE c.order_id=#{orderId} AND c.status='REFUNDED'
                  AND ai.source_kind='UNSHIPPED') refundedUnshippedQuantity,
              (SELECT COALESCE(SUM(ai.quantity),0) FROM after_sale_items ai
                JOIN after_sale_cases c ON c.id=ai.case_id
                WHERE c.order_id=#{orderId} AND c.status='REFUNDED'
                  AND ai.source_kind='SHIPMENT') refundedShippedQuantity,
              (SELECT COALESCE(SUM(ai.quantity),0) FROM after_sale_items ai
                JOIN after_sale_cases c ON c.id=ai.case_id
                JOIN shipments s ON s.id=ai.source_shipment_id
                WHERE c.order_id=#{orderId} AND c.status='REFUNDED'
                  AND s.status='CONFIRMED') refundedConfirmedQuantity,
              (SELECT COALESCE(SUM(ai.quantity),0) FROM after_sale_items ai
                JOIN after_sale_cases c ON c.id=ai.case_id
                WHERE c.order_id=#{orderId} AND c.status='REFUNDED'
                  AND ai.source_kind='LEGACY_UNKNOWN') unknownRefundedQuantity,
              (SELECT COUNT(*) FROM shipments s WHERE s.order_id=#{orderId}
                  AND s.allocation_state='NEEDS_REVIEW') unresolvedAllocationCount
            """)
    OrderFulfillmentVO fulfillmentProgress(@Param("orderId") Long orderId);

    @Insert("""
            INSERT INTO order_items (order_id, sku_id, product_name, sku_spec, quantity, unit_price)
            VALUES (#{orderId}, #{skuId}, #{productName}, #{skuSpec}, #{quantity}, #{unitPrice})
            """)
    void insertCheckoutItem(@Param("orderId") Long orderId, @Param("skuId") Long skuId,
                            @Param("productName") String productName, @Param("skuSpec") String skuSpec,
                            @Param("quantity") Integer quantity, @Param("unitPrice") BigDecimal unitPrice);

    @Select("SELECT * FROM orders WHERE id = #{orderId} FOR UPDATE")
    Order selectForUpdate(@Param("orderId") Long orderId);

    @Update("UPDATE orders SET status = 'PAID', paid_at = #{paidAt} WHERE id = #{orderId} AND status = 'UNPAID'")
    int markPaid(@Param("orderId") Long orderId, @Param("paidAt") java.time.LocalDateTime paidAt);

    @Update("UPDATE orders SET status = 'CANCELLED' WHERE id = #{orderId} AND status = 'UNPAID'")
    int markCancelled(@Param("orderId") Long orderId);

    @Update("UPDATE orders SET fulfillment_state = 'STOPPED' WHERE id = #{orderId} "
            + "AND status = 'PAID' AND settlement_kind IN ('ZERO_CASH','POINTS') "
            + "AND fulfillment_state = 'READY'")
    int stopZeroCashFulfillment(@Param("orderId") Long orderId);

    @Select("SELECT NOT EXISTS(SELECT 1 FROM order_items i WHERE i.order_id=#{orderId} AND i.quantity>"
            + "(SELECT COALESCE(SUM(ai.quantity),0) FROM after_sale_items ai "
            + "JOIN after_sale_cases c ON c.id=ai.case_id AND c.status='REFUNDED' WHERE ai.order_item_id=i.id))")
    boolean fullyRefunded(@Param("orderId") Long orderId);

    @Update("UPDATE orders o JOIN marketing_participations p ON p.order_id=o.id "
            + "JOIN marketing_groups g ON g.id=p.group_id SET o.fulfillment_state='READY' "
            + "WHERE g.id=#{groupId} AND g.status='SUCCEEDED' AND o.status='PAID' "
            + "AND o.fulfillment_state='WAIT_GROUP'")
    int releaseSucceededGroup(@Param("groupId") Long groupId);

    @Select("SELECT status FROM marketing_groups WHERE id=#{groupId}")
    String groupStatus(@Param("groupId") Long groupId);

    @Update("UPDATE orders SET fulfillment_state='STOPPED' WHERE id=#{orderId} "
            + "AND status='PAID' AND fulfillment_state='WAIT_GROUP'")
    int stopFailedGroupFulfillment(@Param("orderId") Long orderId);

    @Select("SELECT o.id FROM orders o JOIN marketing_participations p ON p.order_id=o.id "
            + "JOIN marketing_groups g ON g.id=p.group_id "
            + "LEFT JOIN after_sale_cases c ON c.order_id=o.id "
            + "AND c.request_key=CONCAT('GROUP_FAIL:',o.id) "
            + "WHERE g.status='FAILED' AND o.status='PAID' "
            + "AND o.fulfillment_state IN ('WAIT_GROUP','STOPPED') "
            + "AND (c.id IS NULL OR c.status='REFUNDING') ORDER BY o.id LIMIT #{limit}")
    List<Long> selectFailedGroupRefundIds(@Param("limit") int limit);

    @Update("UPDATE orders SET fully_received_at=#{at} WHERE id=#{orderId} "
            + "AND status='PAID' AND fully_received_at IS NULL")
    int markFullyReceived(@Param("orderId") Long orderId, @Param("at") LocalDateTime at);

    @Update("UPDATE orders SET cancel_queue_at=COALESCE(cancel_queue_at, #{now}), cancel_requested_at=COALESCE(cancel_requested_at, #{now}) WHERE id=#{orderId} AND status='UNPAID'")
    int requestCancellation(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);

    List<Long> selectExpiredIds(@Param("now") java.time.LocalDateTime now,
                                @Param("limit") int limit);

    List<Long> selectCancellationIds(@Param("limit") int limit);

    Long oldestExpiredSeconds(@Param("now") java.time.LocalDateTime now);

    // Claim housekeeping does not change the order's business update time.
    @Update("""
            UPDATE orders SET expiry_claim_token=#{token}, updated_at=updated_at,
                              expiry_claim_until=DATE_ADD(NOW(3), INTERVAL #{leaseSeconds} SECOND)
            WHERE id=#{orderId} AND status='UNPAID' AND (pay_expires_at <= #{now} OR cancel_requested_at IS NOT NULL)
              AND (expiry_claim_until IS NULL OR expiry_claim_until <= NOW(3))
            """)
    int claimExpiry(@Param("orderId") Long orderId, @Param("token") String token,
                    @Param("now") LocalDateTime now, @Param("leaseSeconds") long leaseSeconds);

    @Select("""
            SELECT COUNT(*) FROM orders WHERE id=#{orderId} AND expiry_claim_token=#{token}
              AND expiry_claim_until > NOW(3)
            """)
    int ownsExpiry(@Param("orderId") Long orderId, @Param("token") String token);

    @Update("""
            UPDATE orders SET expiry_claim_token=NULL, expiry_claim_until=NULL,
                cancel_queue_at=CASE WHEN status='UNPAID' AND cancel_requested_at IS NOT NULL THEN NOW(3) ELSE cancel_queue_at END,
                updated_at=updated_at
            WHERE id=#{orderId} AND expiry_claim_token=#{token}
            """)
    int releaseExpiry(@Param("orderId") Long orderId, @Param("token") String token);
}
