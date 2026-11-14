package com.shiwushop.marketing.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 交易在同一事务内使用的服务端活动来源，不接受客户端报价代替。 */
public record MarketingOrderSource(
    /** 活动参与资格记录 ID。 */
    Long participationId,
    /** 关联营销活动 ID。 */
    Long activityId,
    /** 活动商品范围记录 ID，不是 SKU ID。 */
    Long activitySkuId,
    /** 所属店铺 ID。 */
    Long shopId,
    /** 商品（SPU）ID。 */
    Long productId,
    /** 商品规格（SKU）ID。 */
    Long skuId,
    /** 商品件数。 */
    int quantity,
    /** 活动参与资格对应的现金应付总额，单位元。 */
    BigDecimal cashTotal,
    /** 本单或本店应付的积分最小单位数；100 单位为 1 积分。 */
    long pointUnits,
    /** 交易类型：NORMAL 普通、FLASH 秒杀、GROUP 拼团、BARGAIN 砍价、POINTS 积分兑换。 */
    String businessType,
    /** 有效截止时间。 */
    LocalDateTime expiresAt,
    /** 拼团团实例 ID。 */
    Long groupId,
    /** 砍价实例 ID。 */
    Long bargainId
) {
}
