package com.shiwushop.commerce.mapper.shipment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.commerce.entity.shipment.Shipment;
import com.shiwushop.entity.status.ConfirmSource;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface ShipmentMapper extends BaseMapper<Shipment> {
    Shipment selectByOrderId(@Param("orderId") Long orderId);
    Shipment selectByOrderIdForUpdate(@Param("orderId") Long orderId);
    int confirm(@Param("shipmentId") Long shipmentId, @Param("source") ConfirmSource source,
                @Param("now") LocalDateTime now);
    List<Long> selectDueIds(@Param("now") LocalDateTime now, @Param("limit") int limit);
    Long oldestDueSeconds(@Param("now") LocalDateTime now);
    BigDecimal refundedAmount(@Param("orderId") Long orderId);
    int unresolvedAfterSaleCount(@Param("orderId") Long orderId);
}
