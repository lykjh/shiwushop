package com.shiwushop.commerce.service;

import com.shiwushop.commerce.dto.shipment.ShipmentCreateDTO;
import com.shiwushop.commerce.vo.shipment.ShipmentVO;
import java.util.List;

public interface ShipmentService {
    /** 商家为所属店铺的已付款订单登记发货信息。 */
    ShipmentVO ship(Long userId, Long shopId, Long orderId, ShipmentCreateDTO request);
    /** 商家查看店铺订单的物流详情。 */
    ShipmentVO merchantDetail(Long userId, Long shopId, Long orderId);
    /** 买家查看本人订单的物流详情。 */
    ShipmentVO buyerDetail(Long userId, Long orderId);
    /** 买家确认本人订单收货。 */
    ShipmentVO confirmByBuyer(Long userId, Long orderId);
    /** 后台任务对一条到期物流执行自动确认。 */
    void confirmDue(Long shipmentId);
    /** 查询到达自动确认时间的物流 ID，供后台任务逐条处理。 */
    List<Long> dueShipmentIds();
}
