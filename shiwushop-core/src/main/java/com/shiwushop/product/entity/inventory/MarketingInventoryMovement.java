package com.shiwushop.product.entity.inventory;

import lombok.Data;

@Data
public class MarketingInventoryMovement {
    /** 当前记录 ID。 */
    private Long id;
    /** 商品规格（SKU）ID。 */
    private Long skuId;
    /** 活动商品范围记录 ID，不是 SKU ID。 */
    private Long activitySkuId;
    /** 本次操作人用户 ID。 */
    private Long actorUserId;
    /** 营销划拨库存原因代码。 */
    private String reason;
    /** 本次库存增减件数，正数增加、负数减少。 */
    private Integer delta;
    /** 本次变动后的余额。 */
    private Integer balanceAfter;
    /** 账本操作幂等键，防止重复记账。 */
    private String operationKey;
}
