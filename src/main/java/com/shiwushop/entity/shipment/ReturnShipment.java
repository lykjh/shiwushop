package com.shiwushop.entity.shipment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("return_shipments")
public class ReturnShipment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private String carrierName;
    private String trackingNo;
    private LocalDateTime sentAt;
    private LocalDateTime receivedAt;
    private Long receivedByUserId;
}
