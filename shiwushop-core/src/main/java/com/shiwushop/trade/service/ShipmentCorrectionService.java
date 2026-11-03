package com.shiwushop.trade.service;

import com.shiwushop.trade.dto.shipment.correction.ShipmentCorrectionRequestDTO;
import com.shiwushop.trade.dto.shipment.correction.ShipmentCorrectionDecisionDTO;
import com.shiwushop.trade.dto.shipment.correction.ShipmentCorrectionStatus;
import com.shiwushop.trade.vo.shipment.correction.ShipmentCorrectionHistoryPageVO;
import com.shiwushop.trade.vo.shipment.correction.ShipmentCorrectionAdminPageVO;
import com.shiwushop.trade.vo.shipment.correction.ShipmentCorrectionRequestVO;

public interface ShipmentCorrectionService {
    ShipmentCorrectionRequestVO request(Long userId, Long shopId, Long orderId, ShipmentCorrectionRequestDTO body);
    ShipmentCorrectionRequestVO decide(Long userId, Long id, ShipmentCorrectionDecisionDTO body);
    ShipmentCorrectionHistoryPageVO buyerHistory(Long userId, Long orderId);
    ShipmentCorrectionHistoryPageVO merchantHistory(Long userId, Long shopId, Long orderId);
    ShipmentCorrectionRequestVO merchantRequest(Long userId, Long shopId, Long orderId, Long correctionId);
    ShipmentCorrectionAdminPageVO adminList(ShipmentCorrectionStatus status, Long afterId, int size);
}
