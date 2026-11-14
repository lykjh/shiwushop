package com.shiwushop.marketing.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import tools.jackson.databind.annotation.JsonDeserialize;

public record ShopChoice(
    /** 所属店铺 ID。 */
    @NotNull @Positive @JsonDeserialize(using = JsonLongValue.class) Long shopId,
    /** 选券方式：AUTO 推荐、NONE 不用券、SELECT 指定券；下单只接受后两种，缺省 NONE。 */
    @Pattern(regexp = "AUTO|NONE|SELECT") String couponMode,
    /** SELECT 模式指定的用户券 ID；其他模式不填。 */
    @Positive @JsonDeserialize(using = JsonLongValue.class) Long userCouponId,
    /** 选用的满减活动 ID。 */
    @Positive @JsonDeserialize(using = JsonLongValue.class) Long reductionActivityId,
    /** 选用的满赠活动 ID。 */
    @Positive @JsonDeserialize(using = JsonLongValue.class) Long giftActivityId
) {
    public ShopChoice {
        if (couponMode == null)
            couponMode = "NONE";
    }

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("不接受店铺优惠字段：" + field);
    }
}
