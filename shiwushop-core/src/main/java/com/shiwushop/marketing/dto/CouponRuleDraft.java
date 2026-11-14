package com.shiwushop.marketing.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import tools.jackson.databind.annotation.JsonDeserialize;

public record CouponRuleDraft(
    /** 单张优惠券面额，单位元。 */
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal faceAmount,
    /** 享受该优惠所需的门槛金额，单位元。 */
    @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal thresholdAmount,
    /** 优惠券模板总发行张数上限。 */
    @NotNull @Positive @JsonDeserialize(using = JsonIntegerValue.class) Integer issueLimit,
    /** 每个账号累计可获得的券张数上限。 */
    @NotNull @Positive @JsonDeserialize(using = JsonIntegerValue.class) Integer perUserLimit,
    /** 优惠券领取截止时间。 */
    @NotNull OffsetDateTime claimEndAt,
    /** 优惠券有效期天数。 */
    @NotNull @Min(1) @Max(366) @JsonDeserialize(using = JsonIntegerValue.class) Integer validityDays,
    /** 优惠券模板指定的统一到期时间。 */
    OffsetDateTime fixedExpireAt,
    /** 发券方式：MEMBERSHIP 月券、CLAIM 领取、CODE 兑换、COMPENSATION 补偿。 */
    @NotNull @Pattern(regexp = "MEMBERSHIP|CLAIM|CODE|COMPENSATION") String grantMode,
    /** MEMBERSHIP 月券模板面向的会员等级；其他发券方式不填。 */
    @Pattern(regexp = "ORDINARY|SILVER|GOLD|PLATINUM") String memberLevelCode
) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("不接受券规则字段：" + field);
    }
}
