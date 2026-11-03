package com.shiwushop.trade.vo.shipment.correction;

import java.util.List;

public record ShipmentCorrectionHistoryPageVO(int currentVersion, List<ShipmentCorrectionHistoryVO> items) {}
