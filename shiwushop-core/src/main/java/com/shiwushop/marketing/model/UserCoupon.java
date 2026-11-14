package com.shiwushop.marketing.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class UserCoupon {
    /** 用户券实例 ID。 */
    private Long id;
    /** 发券义务 ID；同一义务可产生多张券。 */
    private Long grantId;
    /** 同一发券义务下的券序号。 */
    private int ordinalNo;
    /** 所属用户 ID。 */
    private Long userId;
    /** 关联营销活动 ID。 */
    private Long activityId;
    /** 用户券实例名称。 */
    private String name;
    /** 单张优惠券面额，单位元。 */
    private BigDecimal faceAmount;
    /** 享受该优惠所需的门槛金额，单位元。 */
    private BigDecimal thresholdAmount;
    /** 优惠券生效时间。 */
    private LocalDateTime validFrom;
    /** 有效截止时间。 */
    private LocalDateTime expiresAt;
    /** 券状态：AVAILABLE 可用、RESERVED 占用、USED 核销、EXPIRED 过期。 */
    private String status;
    /** 当前占用该券的订单 ID。 */
    private Long reservedOrderId;
    /** 已核销该券的订单 ID。 */
    private Long usedOrderId;
    /** 优惠券占用版本，区分不同轮次的占用与释放。 */
    private long reservationVersion;
    /** 当前优惠券占用到期时间。 */
    private LocalDateTime reservedUntil;
}
