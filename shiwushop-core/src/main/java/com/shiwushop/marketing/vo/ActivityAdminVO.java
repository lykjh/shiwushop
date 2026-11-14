package com.shiwushop.marketing.vo;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.shiwushop.marketing.dto.*;

import java.time.OffsetDateTime;
import java.util.List;

public record ActivityAdminVO(
    /** 当前记录 ID。 */
    Long id,
    /** 同一活动跨修订版本的业务代码。 */
    String activityCode,
    /** 活动版本号。 */
    int versionNo,
    /** 活动状态：DRAFT、PENDING_REVIEW、APPROVED、RUNNING、ENDED、REJECTED、PAUSED、TERMINATED。 */
    String status,
    /** 名称。 */
    String name,
    /** 营销活动类型。 */
    String type,
    /** 活动开始时间。 */
    OffsetDateTime startAt,
    /** 活动结束时间。 */
    OffsetDateTime endAt,
    /** 允许参与活动的会员等级代码列表。 */
    List<String> eligibleLevelCodes,
    /** 面向用户展示的活动规则说明。 */
    String rulesText,
    /** 活动适用的商品范围。 */
    List<Scope> scopes,
    /** 满减或满赠门槛阶梯。 */
    List<TierDraft> tiers,
    /** 发券模板规则。 */
    CouponRuleDraft couponRule,
    /** 平台及商家的预算承担方案。 */
    List<BudgetProposal> budgetProposals,
    /** 记录创建时间。 */
    OffsetDateTime createdAt,
    /** 审核完成时间。 */
    OffsetDateTime reviewedAt,
    /** 审核意见。 */
    String reviewReason,
    /** 活动所需承担协议是否均已确认。 */
    boolean agreementsComplete
) {
    public record Scope(
        /** 当前记录 ID。 */
        Long id,
        /** 所属店铺 ID。 */
        Long shopId,
        /** 该记录对应的活动规则配置。 */
        @JsonUnwrapped ScopeDraft draft
    ) {
    }

    public record Version(
        /** 营销活动详情。 */
        ActivityAdminVO activity,
        /** 活动版本校验标识，用于并发编辑校验。 */
        String etag
    ) {
    }

    public record Page(
        /** 当前对象的明细列表。 */
        List<ActivityAdminVO> items,
        /** 是否还有下一页。 */
        boolean hasMore,
        /** 下一页查询游标。 */
        Long nextCursor
    ) {
    }
}
