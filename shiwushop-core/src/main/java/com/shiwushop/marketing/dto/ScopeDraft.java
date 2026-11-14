package com.shiwushop.marketing.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

import tools.jackson.databind.annotation.JsonDeserialize;

public record ScopeDraft(
    /** 商品规格（SKU）ID。 */
    @NotNull @Positive @JsonDeserialize(using = JsonLongValue.class) Long skuId,
    /** 商品在活动中的角色：SALE 销售、GIFT 赠品、REDEEM 积分兑换。 */
    @NotNull @Pattern(regexp = "SALE|GIFT|REDEEM") String scopeRole,
    /** 白银、黄金及铂金三个等级的会员单价。 */
    @Valid MemberPrices memberPrices,
    /** 活动单价，单位元。 */
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal activityPrice,
    /** 单账号在本活动中的购买件数上限。 */
    @Positive @JsonDeserialize(using = JsonIntegerValue.class) Integer perUserLimit,
    /** 活动商品总销售上限。 */
    @Positive @JsonDeserialize(using = JsonIntegerValue.class) Integer salesLimit,
    /** 成团人数；本期请求契约限定为 2 人。 */
    @Min(2) @Max(2) @JsonDeserialize(using = JsonIntegerValue.class) Integer teamSize,
    /** 活动参与资格有效时长，单位秒。 */
    @Min(60) @Max(86400) @JsonDeserialize(using = JsonIntegerValue.class) Integer participationSeconds,
    /** 砍价起始单价，单位元。 */
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal bargainStartPrice,
    /** 砍价目标单价，单位元。 */
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal bargainTargetPrice,
    /** 完成砍价所需的助力人数。 */
    @Min(1) @Max(100) @JsonDeserialize(using = JsonIntegerValue.class) Integer requiredHelpers,
    /** 每个账号在该活动每日助力次数上限。 */
    @Min(1) @Max(100) @JsonDeserialize(using = JsonIntegerValue.class) Integer dailyHelpLimit,
    /** 该活动商品允许同时进行的砍价场次总上限。 */
    @Min(1) @Max(10000) @JsonDeserialize(using = JsonIntegerValue.class) Integer maxOpenSessions,
    /** 单件兑换所需的积分最小单位数；100 单位为 1 积分。 */
    @Positive @JsonDeserialize(using = JsonLongValue.class) Long pointCostUnits,
    /** 单件赠品或兑换商品的核定成本，单位元。 */
    @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal unitCostAmount
) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("不接受商品范围字段：" + field);
    }
}
