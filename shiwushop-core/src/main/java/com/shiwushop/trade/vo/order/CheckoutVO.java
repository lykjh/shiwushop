package com.shiwushop.trade.vo.order;

import com.shiwushop.trade.entity.order.Order;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import java.math.BigDecimal;
import java.util.List;

public record CheckoutVO(
    /** 本次处理的订单列表。 */
    List<PlacedOrder> orders,
    /** 现金应付总额，单位元。 */
    BigDecimal totalAmount,
    /** 本次结算应付的积分最小单位总数；100 单位为 1 积分。 */
    long totalPointUnits
) {
    public record PlacedOrder(
        /** 订单 ID。 */
        Long id,
        /** 所属店铺 ID。 */
        Long shopId,
        /** 现金应付总额，单位元。 */
        BigDecimal totalAmount,
        /** 订单支付状态：UNPAID 待支付、PAID 已支付、CANCELLED 已取消。 */
        String status,
        /** 交易类型：NORMAL 普通、FLASH 秒杀、GROUP 拼团、BARGAIN 砍价、POINTS 积分兑换。 */
        String businessType,
        /** 结算方式：CASH 现金、ZERO_CASH 零现金、POINTS 积分。 */
        String settlementKind,
        /** 履约门禁：READY 可履约、WAIT_GROUP 等待成团、STOPPED 已停止。 */
        String fulfillmentState,
        /** 活动参与资格记录 ID。 */
        Long participationId,
        /** 拼团团实例 ID。 */
        Long groupId,
        /** 砍价实例 ID。 */
        Long bargainId,
        /** 本单或本店应付的积分最小单位数；100 单位为 1 积分。 */
        long pointUnits,
        /** 订单付款截止时间。 */
        OffsetDateTime payExpiresAt
    ) {
        public static PlacedOrder from(Order order) {
            return from(order, null, null, null);
        }

        public static PlacedOrder from(Order order, Long groupId, Long participationId, Long bargainId) {
            return new PlacedOrder(order.getId(), order.getShopId(), order.getTotalAmount(), order.getStatus().name(),
                    order.getBusinessType(), order.getSettlementKind(), order.getFulfillmentState(),
                    participationId, groupId, bargainId, 0,
                    "CASH".equals(order.getSettlementKind()) ? order.getPayExpiresAt()
                            .atOffset(ZoneOffset.ofHours(8)) : null);
        }
    }
}
