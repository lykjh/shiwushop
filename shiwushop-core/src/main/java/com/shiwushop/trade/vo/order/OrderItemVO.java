package com.shiwushop.trade.vo.order;

import java.math.BigDecimal;

public record OrderItemVO(
    /** 关联订单明细 ID。 */
    Long orderItemId,
    /** 商品（SPU）ID。 */
    Long productId,
    /** 商品规格（SKU）ID。 */
    Long skuId,
    /** 下单时冻结的商品名称。 */
    String productName,
    /** 成交时的规格描述快照。 */
    String spec,
    /** 商品件数。 */
    Integer quantity,
    /** 成交单价快照，不随当前商品价格变化，单位元。 */
    BigDecimal unitPrice,
    /** 当前单价乘件数的小计，单位元。 */
    BigDecimal lineAmount,
    /** 订单明细类型：SALE 销售、GIFT 赠品、REDEEM 积分兑换。 */
    String itemKind,
    /** 优惠前的普通单价快照，单位元。 */
    BigDecimal originalUnitPrice,
    /** 分配给当前对象的优惠金额，单位元。 */
    BigDecimal discountAmount,
    /** 扣除优惠后的应付金额，单位元。 */
    BigDecimal payableAmount,
    /** 关联营销活动 ID。 */
    Long activityId,
    /** 该明细参与的优惠及金额。 */
    java.util.List<Benefit> benefits
) {
    public record Benefit(
        /** 关联订单明细 ID。 */
        Long orderItemId,
        /** 关联营销活动 ID。 */
        Long activityId,
        /** 该项优惠类型，如 MEMBER、LIMITED、COUPON 或 FULL_REDUCTION。 */
        String kind,
        /** 分配给当前对象的优惠金额，单位元。 */
        BigDecimal discountAmount,
        /** 当前优惠由平台承担的金额，单位元。 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) BigDecimal platformAmount,
        /** 当前优惠由商家承担的金额，单位元。 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) BigDecimal merchantAmount
    ) {
    }
}
