package com.shiwushop.marketing.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CouponCode {
    /** 兑换码记录 ID。 */
    private Long id;
    /** 关联营销活动 ID。 */
    private Long activityId;
    /** 兑换码摘要，持久化不保存兑换码明文。 */
    private byte[] codeHash;
    /** 兑换码类型：PUBLIC 公共码、SINGLE 单次码。 */
    private String codeKind;
    /** 兑换码允许的最大兑换次数。 */
    private int maxRedemptions;
    /** 兑换码已成功兑换次数。 */
    private int redeemedCount;
    /** 有效截止时间。 */
    private LocalDateTime expiresAt;
    /** 兑换码状态：ACTIVE 启用、DISABLED 停用。 */
    private String status;
    /** 创建人用户 ID。 */
    private Long creatorUserId;
    /** 创建兑换码批次的幂等键。 */
    private String creationRequestKey;
    /** 兑换码创建请求的内容哈希。 */
    private byte[] creationRequestHash;
    /** 兑换码在创建批次中的序号。 */
    private int creationOrdinal;
}
