package com.shiwushop.marketing.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MarketingActivity {
    /** 营销活动 ID。 */
    private Long id;
    /** 同一活动跨修订版本的业务代码。 */
    private String activityCode;
    /** 活动版本号。 */
    private int versionNo;
    /** 营销活动名称。 */
    private String name;
    /** 营销活动类型。 */
    private String activityType;
    /** 活动状态：DRAFT、PENDING_REVIEW、APPROVED、RUNNING、ENDED、REJECTED、PAUSED、TERMINATED。 */
    private String status;
    /** 活动开始时间。 */
    private LocalDateTime startAt;
    /** 活动结束时间。 */
    private LocalDateTime endAt;
    /** 允许参与活动的会员等级代码列表。 */
    private String eligibleLevelCodes;
    /** 创建人用户 ID。 */
    private Long creatorUserId;
    /** 审核人用户 ID。 */
    private Long reviewerUserId;
    /** 审核完成时间。 */
    private LocalDateTime reviewedAt;
    /** 审核意见。 */
    private String reviewReason;
    /** 面向用户展示的活动规则说明。 */
    private String rulesText;
    /** 本修订版本所替代的旧活动 ID。 */
    private Long supersedesId;
    /** 创建请求所属的幂等作用域。 */
    private String requestScope;
    /** 请求幂等键，用于识别同一业务请求。 */
    private String requestKey;
    /** 原请求内容哈希，用于核对同键重放是否改变参数。 */
    private byte[] requestHash;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 记录最近更新时间。 */
    private LocalDateTime updatedAt;
}
