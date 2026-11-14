package com.shiwushop.trade.vo.shipment;

import java.time.LocalDateTime;
import java.util.List;

public record ShipmentVO(
    /** 关联发货包裹 ID。 */
    Long shipmentId,
    /** 关联订单 ID。 */
    Long orderId,
    /** 包裹状态：SHIPPED 已发货、CONFIRMED 已收货。 */
    String status,
    /** 物流承运商名称。 */
    String carrierName,
    /** 物流运单号。 */
    String trackingNo,
    /** 包裹发货时间。 */
    LocalDateTime shippedAt,
    LocalDateTime simulatedInTransitAt,
    LocalDateTime carrierSignedAt,
    /** 该包裹签收后 10 天的自动收货截止时间。 */
    LocalDateTime autoConfirmAt,
    /** 包裹确认收货时间。 */
    LocalDateTime confirmedAt,
    /** 确认收货来源：BUYER 买家、SYSTEM 系统自动确认。 */
    String confirmSource,
    /** 当前对象的明细列表。 */
    List<Item> items,
    boolean simulationEnabled
) {
    public record Item(
        /** 关联订单明细 ID。 */
        Long orderItemId,
        /** 商品件数。 */
        int quantity
    ) {
    }
}
