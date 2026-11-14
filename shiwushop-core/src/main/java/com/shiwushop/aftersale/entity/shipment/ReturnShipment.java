package com.shiwushop.aftersale.entity.shipment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("return_shipments")
public class ReturnShipment {
    /** 退货物流 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联售后单 ID。 */
    private Long caseId;
    /** 物流承运商名称。 */
    private String carrierName;
    /** 物流运单号。 */
    private String trackingNo;
    /** 买家提交退货物流的时间。 */
    private LocalDateTime sentAt;
    /** 商家确认收到退货的时间。 */
    private LocalDateTime receivedAt;
    /** 确认收到退货的用户 ID。 */
    private Long receivedByUserId;
}
