package com.shiwushop.marketing.model;

import com.shiwushop.marketing.dto.MemberPrices;
import com.shiwushop.marketing.dto.ScopeDraft;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ActivityScope {
    /** 活动商品范围 ID。 */
    private Long id;
    /** 关联营销活动 ID。 */
    private Long activityId;
    /** 商品规格（SKU）ID。 */
    private Long skuId;
    /** 所属店铺 ID。 */
    private Long shopId;
    /** 商品在活动中的角色：SALE 销售、GIFT 赠品、REDEEM 积分兑换。 */
    private String scopeRole;
    /** 白银会员单价，单位元。 */
    private BigDecimal silver;
    /** 黄金会员单价，单位元。 */
    private BigDecimal gold;
    /** 铂金会员单价，单位元。 */
    private BigDecimal platinum;
    /** 活动单价，单位元。 */
    private BigDecimal activityPrice;
    /** 每个账号的累计参与上限。 */
    private Integer perUserLimit;
    /** 活动商品总销售上限。 */
    private Integer salesLimit;
    /** 活动配置的成团人数。 */
    private Integer teamSize;
    /** 活动参与资格有效时长，单位秒。 */
    private Integer participationSeconds;
    /** 砍价起始单价，单位元。 */
    private BigDecimal bargainStartPrice;
    /** 砍价目标单价，单位元。 */
    private BigDecimal bargainTargetPrice;
    /** 完成砍价所需的助力人数。 */
    private Integer requiredHelpers;
    /** 每个账号在该活动每日助力次数上限。 */
    private Integer dailyHelpLimit;
    /** 该活动商品允许同时进行的砍价场次总上限。 */
    private Integer maxOpenSessions;
    /** 单件兑换所需的积分最小单位数；100 单位为 1 积分。 */
    private Long pointCostUnits;
    /** 单件赠品或兑换商品的核定成本，单位元。 */
    private BigDecimal unitCostAmount;

    public ScopeDraft draft() {
        return new ScopeDraft(skuId, scopeRole, silver == null ? null : new MemberPrices(silver, gold, platinum),
                activityPrice, perUserLimit, salesLimit, teamSize, participationSeconds, bargainStartPrice,
                bargainTargetPrice, requiredHelpers, dailyHelpLimit, maxOpenSessions, pointCostUnits, unitCostAmount);
    }

    @Data
    public static class Insert {
        /** 当前记录 ID。 */
        private Long id;
        /** 关联营销活动 ID。 */
        private final Long activityId;
        /** 该记录对应的活动规则配置。 */
        private final ScopeDraft draft;
    }
}
