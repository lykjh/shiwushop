package com.shiwushop.mapper.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.entity.order.OrderItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface OrderItemMapper extends BaseMapper<OrderItem> {
    @Select("SELECT id FROM order_items WHERE order_id = #{orderId} AND sku_id = #{skuId} ORDER BY id DESC LIMIT 1")
    Long selectLatestId(@Param("orderId") Long orderId, @Param("skuId") Long skuId);

    List<OrderItem> selectForOrder(@Param("orderId") Long orderId);
}
