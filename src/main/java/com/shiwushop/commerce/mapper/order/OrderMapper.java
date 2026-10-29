package com.shiwushop.commerce.mapper.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.commerce.entity.order.Order;
import com.shiwushop.commerce.vo.order.OrderSummaryVO;
import org.apache.ibatis.annotations.Param;
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

    List<Long> selectExpiredIds(@Param("now") java.time.LocalDateTime now,
                                @Param("limit") int limit);
    Long oldestExpiredSeconds(@Param("now") java.time.LocalDateTime now);

    // Claim housekeeping does not change the order's business update time.
    @Update("""
            UPDATE orders SET expiry_claim_token=#{token}, updated_at=updated_at,
                              expiry_claim_until=DATE_ADD(NOW(3), INTERVAL #{leaseSeconds} SECOND)
            WHERE id=#{orderId} AND status='UNPAID' AND pay_expires_at <= #{now}
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
                updated_at=updated_at
            WHERE id=#{orderId} AND expiry_claim_token=#{token}
            """)
    int releaseExpiry(@Param("orderId") Long orderId, @Param("token") String token);
}
