package com.shiwushop.commerce.mapper.order;

import com.shiwushop.commerce.entity.order.Order;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

public interface CheckoutRequestMapper {
    @Insert("""
            INSERT INTO checkout_requests (user_id, idempotency_key, request_hash, status)
            VALUES (#{userId}, #{key}, #{hash}, 'PROCESSING')
            """)
    int insertProcessing(@Param("userId") Long userId, @Param("key") String key,
                         @Param("hash") byte[] hash);

    Map<String, Object> selectForUpdate(@Param("userId") Long userId, @Param("key") String key);

    @Insert("INSERT INTO checkout_request_orders (checkout_request_id, order_id) VALUES (#{requestId}, #{orderId})")
    int linkOrder(@Param("requestId") Long requestId, @Param("orderId") Long orderId);

    @Update("UPDATE checkout_requests SET status = 'COMPLETED' WHERE id = #{requestId}")
    int markCompleted(@Param("requestId") Long requestId);

    List<Order> selectOrders(@Param("requestId") Long requestId);
}
