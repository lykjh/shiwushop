package com.shiwushop.product.vo.query.review;

import lombok.Data;

@Data
public class ReviewEligibility {
    /** 关联订单明细 ID。 */
    private Long orderItemId;
    /** 关联订单 ID。 */
    private Long orderId;
    /** 购买者用户 ID。 */
    private Long buyerUserId;
    /** 商品（SPU）ID。 */
    private Long productId;
    /** 商品件数。 */
    private Integer quantity;
    /** 已退款商品件数。 */
    private Integer refundedQuantity;
    /** 累计已确认收货件数。 */
    private Integer confirmedQuantity;
    /** 已退款且原包裹已确认收货的件数。 */
    private Integer refundedConfirmedQuantity;
    /** 订单支付状态。 */
    private String orderStatus;
}
