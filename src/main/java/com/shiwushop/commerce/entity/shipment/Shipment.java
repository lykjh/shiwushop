package com.shiwushop.commerce.entity.shipment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shiwushop.commerce.entity.status.ConfirmSource;
import com.shiwushop.commerce.entity.status.ShipmentStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("shipments")
public class Shipment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String carrierName;
    private String trackingNo;
    private ShipmentStatus status;
    private Long shippedByUserId;
    private LocalDateTime shippedAt;
    private LocalDateTime autoConfirmAt;
    private LocalDateTime confirmedAt;
    private ConfirmSource confirmSource;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
