package com.shiwushop.trade.mapper.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.trade.entity.order.OrderItem;
import com.shiwushop.member.dto.RewardLine;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface OrderItemMapper extends BaseMapper<OrderItem> {
    @Select("""
            SELECT a.order_item_id,a.activity_id,a.benefit_kind kind,a.amount discount_amount,
              IF(#{merchant},a.platform_amount,NULL) platform_amount,IF(#{merchant},a.merchant_amount,NULL) merchant_amount
            FROM order_promotion_allocations a JOIN order_items i ON i.id=a.order_item_id
            WHERE i.order_id=#{order} ORDER BY i.id,a.id
            """)
    List<com.shiwushop.trade.vo.order.OrderItemVO.Benefit> benefits(@Param("order") long order,
                                                                    @Param("merchant") boolean merchant);

    List<OrderItem> selectForOrder(@Param("orderId") Long orderId);

    @Select("""
            SELECT item.id AS order_item_id,COALESCE(item.payable_amount,item.line_amount) AS paid_amount,
              COALESCE((SELECT SUM(refunded.refund_amount) FROM after_sale_items refunded
                JOIN after_sale_cases sale ON sale.id=refunded.case_id
                WHERE refunded.order_item_id=item.id AND sale.status='REFUNDED'),0) AS refunded_amount
            FROM order_items item WHERE item.order_id=#{orderId} ORDER BY item.id
            """)
    List<RewardLine> rewardLines(Long orderId);
}
