package com.shiwushop.aftersale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shiwushop.aftersale.entity.status.AfterSaleStatus;
import com.shiwushop.aftersale.entity.status.AfterSaleType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("after_sale_cases")
public class AfterSaleCase {
    /** 售后单 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联订单 ID。 */
    private Long orderId;
    /** 请求幂等键，用于识别同一业务请求。 */
    private String requestKey;
    /** 售后类型：REFUND_ONLY 仅退款、RETURN_REFUND 退货退款。 */
    private AfterSaleType type;
    /** 售后单状态，取值见 AfterSaleStatus。 */
    private AfterSaleStatus status;
    /** 买家申请售后的原因。 */
    private String reason;
    /** 售后发起来源。 */
    private String caseOrigin;
    /** 本次售后申请的现金退款金额，单位元。 */
    private BigDecimal refundAmount;
    /** 审核人用户 ID。 */
    private Long reviewedByUserId;
    /** 审核完成时间。 */
    private LocalDateTime reviewedAt;
    /** 审核意见。 */
    private String reviewReason;
    /** 请求发起时间。 */
    private LocalDateTime requestedAt;
    /** 业务处理结束时间。 */
    private LocalDateTime finishedAt;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 记录最近更新时间。 */
    private LocalDateTime updatedAt;
}
