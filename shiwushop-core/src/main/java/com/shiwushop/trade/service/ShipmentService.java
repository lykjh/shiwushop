package com.shiwushop.trade.service;

import com.shiwushop.trade.dto.shipment.ShipmentCreateDTO;
import com.shiwushop.trade.dto.shipment.ShipmentPackageCreateDTO;
import com.shiwushop.trade.vo.shipment.ShipmentVO;

import java.util.List;

public interface ShipmentService {
    /** 商家为所属店铺的已付款订单登记发货信息。 */
    ShipmentVO ship(Long userId, Long shopId, Long orderId, ShipmentCreateDTO request);

    ShipmentVO shipPackage(Long userId, Long shopId, Long orderId, ShipmentPackageCreateDTO request);

    List<ShipmentVO> merchantPackages(Long userId, Long shopId, Long orderId);

    List<ShipmentVO> buyerPackages(Long userId, Long orderId);

    /** 商家查看店铺订单的物流详情。 */
    ShipmentVO merchantDetail(Long userId, Long shopId, Long orderId);

    /** 买家查看本人订单的物流详情。 */
    ShipmentVO buyerDetail(Long userId, Long orderId);

    /** 买家确认本人订单收货。 */
    ShipmentVO confirmByBuyer(Long userId, Long orderId);

    ShipmentVO confirmPackageByBuyer(Long userId, Long orderId, Long shipmentId);

    enum SimulatedTrackingStep { IN_TRANSIT, SIGNED }

    /** 店主在启用演示物流的环境中推进一个包裹的轨迹。 */
    ShipmentVO simulateTracking(Long userId, Long shopId, Long orderId, Long shipmentId, SimulatedTrackingStep step);

    /** 自动确认到期物流；false 仅表示仍需等待原截止，终态或确认完成返回 true。 */
    boolean confirmDue(Long shipmentId);

    /** 售后成功释放未发货余额后，核对剩余件数是否已经全部收齐。 */
    void reconcileReceiptAfterRefund(Long orderId);

    /** 查询到达自动确认时间的物流 ID，供后台任务逐条处理。 */
    List<Long> dueShipmentIds();
}
