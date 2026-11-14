package com.shiwushop.trade.entity.shipment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shiwushop.trade.entity.status.ConfirmSource;
import com.shiwushop.trade.entity.status.ShipmentStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("shipments")
public class Shipment {
    /** 发货包裹 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联订单 ID。 */
    private Long orderId;
    /** 请求幂等键，用于识别同一业务请求。 */
    private String requestKey;
    /** 包裹明细分配状态：COMPLETE 完整、NEEDS_REVIEW 待核对。 */
    private String allocationState;
    /** 物流承运商名称。 */
    private String carrierName;
    /** 物流运单号。 */
    private String trackingNo;
    /** 包裹状态：SHIPPED 已发货、CONFIRMED 已收货。 */
    private ShipmentStatus status;
    /** 执行发货的用户 ID。 */
    private Long shippedByUserId;
    /** 包裹发货时间。 */
    private LocalDateTime shippedAt;
    /** 该包裹发货后 10 天的自动收货截止时间。 */
    private LocalDateTime autoConfirmAt;
    /** 包裹确认收货时间。 */
    private LocalDateTime confirmedAt;
    /** 确认收货来源：BUYER 买家、SYSTEM 系统自动确认。 */
    private ConfirmSource confirmSource;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 记录最近更新时间。 */
    private LocalDateTime updatedAt;
}
