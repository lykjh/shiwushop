package com.shiwushop.trade.entity.shipment;

import lombok.Data;

@Data
public class ShipmentItem {
    /** 关联发货包裹 ID。 */
    private Long shipmentId;
    /** 关联订单明细 ID。 */
    private Long orderItemId;
    /** 商品件数。 */
    private Integer quantity;
}
