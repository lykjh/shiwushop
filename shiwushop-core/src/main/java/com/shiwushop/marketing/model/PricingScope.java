package com.shiwushop.marketing.model;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PricingScope {
    /** 关联营销活动 ID。 */
    private Long activityId;
    /** 同一活动跨修订版本的业务代码。 */
    private String activityCode;
    /** 营销活动类型。 */
    private String activityType;
    /** 允许参与活动的会员等级代码列表。 */
    private String eligibleLevelCodes;
    /** 活动商品范围记录 ID，不是 SKU ID。 */
    private Long activitySkuId;
    /** 商品规格（SKU）ID。 */
    private Long skuId;
    /** 商品单价，单位元。 */
    private BigDecimal price;
    /** 每个账号的累计参与上限。 */
    private Integer perUserLimit;
    /** 活动商品总销售上限。 */
    private Integer salesLimit;
    /** 当前活动待支付占用件数。 */
    private int reservedQty;
    /** 当前活动已支付件数。 */
    private int paidQty;
}
