package com.shiwushop.commerce.service;

import com.shiwushop.commerce.dto.shipment.ShipmentCreateDTO;
import com.shiwushop.commerce.vo.shipment.ShipmentVO;
import java.util.List;

public interface ShipmentService {
    ShipmentVO ship(Long userId, Long shopId, Long orderId, ShipmentCreateDTO request);
    ShipmentVO merchantDetail(Long userId, Long shopId, Long orderId);
    ShipmentVO buyerDetail(Long userId, Long orderId);
    ShipmentVO confirmByBuyer(Long userId, Long orderId);
    void confirmDue(Long shipmentId);
    List<Long> dueShipmentIds();
}
