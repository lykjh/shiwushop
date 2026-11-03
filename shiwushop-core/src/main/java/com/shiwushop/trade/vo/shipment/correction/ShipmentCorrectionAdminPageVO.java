package com.shiwushop.trade.vo.shipment.correction;

import com.shiwushop.trade.entity.shipment.ShipmentCorrection;
import java.util.List;

public record ShipmentCorrectionAdminPageVO(List<ShipmentCorrection> items, boolean hasMore, Long nextCursor) {}
