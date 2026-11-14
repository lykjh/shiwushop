package com.shiwushop.trade.mapper.shipment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.trade.entity.shipment.Shipment;
import com.shiwushop.trade.entity.status.ConfirmSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface ShipmentMapper extends BaseMapper<Shipment> {
    Shipment selectByOrderId(@Param("orderId") Long orderId);

    Shipment selectByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Select("SELECT * FROM shipments WHERE order_id=#{orderId} ORDER BY id")
    List<Shipment> selectAllByOrderId(@Param("orderId") Long orderId);

    @Select("SELECT * FROM shipments WHERE id=#{shipmentId} FOR UPDATE")
    Shipment selectByIdForUpdate(@Param("shipmentId") Long shipmentId);

    @Select("SELECT * FROM shipments WHERE order_id=#{orderId} AND request_key=#{requestKey} FOR UPDATE")
    Shipment selectByRequestKeyForUpdate(@Param("orderId") Long orderId,
                                         @Param("requestKey") String requestKey);

    @Select("SELECT COUNT(*) FROM shipments WHERE order_id=#{orderId}")
    int countByOrderId(@Param("orderId") Long orderId);

    @Select("SELECT COUNT(*) FROM shipments WHERE order_id=#{orderId} AND allocation_state='NEEDS_REVIEW'")
    int unresolvedAllocationCount(@Param("orderId") Long orderId);

    int confirm(@Param("shipmentId") Long shipmentId, @Param("source") ConfirmSource source,
                @Param("now") LocalDateTime now);

    @Update("UPDATE shipments SET simulated_in_transit_at=#{at} WHERE id=#{shipmentId}")
    int recordSimulatedTransit(@Param("shipmentId") Long shipmentId, @Param("at") LocalDateTime at);

    @Update("UPDATE shipments SET carrier_signed_at=#{at}, auto_confirm_at=#{deadline} WHERE id=#{shipmentId}")
    int recordCarrierSigning(@Param("shipmentId") Long shipmentId, @Param("at") LocalDateTime at,
                             @Param("deadline") LocalDateTime deadline);

    /** 按截止时间和 ID 分批读取符合自动收货条件的包裹。 */
    List<Long> selectDueIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    Long oldestDueSeconds(@Param("now") LocalDateTime now);

    long dueCount(@Param("now") LocalDateTime now);

    BigDecimal refundedAmount(@Param("orderId") Long orderId);

    int unresolvedAfterSaleCount(@Param("orderId") Long orderId);
}
