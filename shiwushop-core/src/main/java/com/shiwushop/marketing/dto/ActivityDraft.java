package com.shiwushop.marketing.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.List;

public record ActivityDraft(
    /** 名称。 */
    @NotBlank @Size(max = 120) String name,
    /** 营销活动类型。 */
    @NotNull @Pattern(regexp = "MEMBER_PRICE|COUPON|LIMITED_PRICE|FLASH|GROUP|BARGAIN|FULL_REDUCTION|FULL_GIFT|POINTS") String type,
    /** 活动开始时间。 */
    @NotNull OffsetDateTime startAt,
    /** 活动结束时间。 */
    @NotNull OffsetDateTime endAt,
    /** 允许参与活动的会员等级代码列表。 */
    @NotNull @Size(min = 1, max = 4) List<@NotNull @Pattern(regexp = "ORDINARY|SILVER|GOLD|PLATINUM") String> eligibleLevelCodes,
    /** 面向用户展示的活动规则说明。 */
    @NotBlank @Size(max = 10000) String rulesText,
    /** 活动适用的商品范围。 */
    @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid ScopeDraft> scopes,
    /** 满减或满赠门槛阶梯。 */
    @NotNull @Size(max = 20) List<@NotNull @Valid TierDraft> tiers,
    /** 发券模板规则。 */
    @Valid CouponRuleDraft couponRule,
    /** 平台及商家的预算承担方案。 */
    @NotNull @Size(min = 1, max = 101) List<@NotNull @Valid BudgetProposal> budgetProposals
) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("不接受活动字段：" + field);
    }
}
