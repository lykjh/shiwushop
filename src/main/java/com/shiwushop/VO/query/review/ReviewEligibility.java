package com.shiwushop.vo.query.review;

import lombok.Data;

@Data
public class ReviewEligibility {
    private Long orderItemId;
    private Long orderId;
    private Long buyerUserId;
    private Long productId;
    private Integer quantity;
    private Integer refundedQuantity;
    private String orderStatus;
    private String shipmentStatus;
}
