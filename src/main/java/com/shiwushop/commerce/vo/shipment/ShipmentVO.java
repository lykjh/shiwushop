package com.shiwushop.commerce.vo.shipment;

import java.time.LocalDateTime;

public record ShipmentVO(Long orderId, String status, String carrierName, String trackingNo,
                         LocalDateTime shippedAt, LocalDateTime autoConfirmAt,
                         LocalDateTime confirmedAt, String confirmSource) { }
