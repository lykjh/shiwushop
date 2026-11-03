package com.shiwushop.trade.vo.shipment.correction;

import java.time.LocalDateTime;

public record ShipmentCorrectionHistoryVO(Long id, int originalVersion, String oldCarrierName,
        String oldTrackingNo, String newCarrierName, String newTrackingNo, String reason,
        Long requestedBy, LocalDateTime requestedAt, Long decidedBy, LocalDateTime decidedAt) {}
