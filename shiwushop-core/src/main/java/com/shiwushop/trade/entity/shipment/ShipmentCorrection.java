package com.shiwushop.trade.entity.shipment;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ShipmentCorrection {
    private Long id;
    private Long shipmentId;
    private Long orderId;
    private Long shopId;
    private Integer originalVersion;
    private String oldCarrierName;
    private String oldTrackingNo;
    private String newCarrierName;
    private String newTrackingNo;
    private String reason;
    private Long requestedBy;
    private LocalDateTime requestedAt;
    private String status;
    private Long decidedBy;
    private LocalDateTime decidedAt;
    private String remark;
}
