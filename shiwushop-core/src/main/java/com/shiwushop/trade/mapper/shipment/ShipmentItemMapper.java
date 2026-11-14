package com.shiwushop.trade.mapper.shipment;

import com.shiwushop.trade.entity.shipment.ShipmentItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface ShipmentItemMapper {
    @Insert("INSERT INTO shipment_items (shipment_id,order_item_id,quantity) VALUES (#{shipmentId},#{orderItemId},#{quantity})")
    int insert(@Param("shipmentId") Long shipmentId, @Param("orderItemId") Long orderItemId,
               @Param("quantity") int quantity);

    @Select("SELECT shipment_id AS shipmentId,order_item_id AS orderItemId,quantity "
            + "FROM shipment_items WHERE shipment_id=#{shipmentId} ORDER BY order_item_id")
    List<ShipmentItem> selectForShipment(@Param("shipmentId") Long shipmentId);

    @Select("SELECT COALESCE(SUM(quantity),0) FROM shipment_items WHERE order_item_id=#{orderItemId}")
    int shippedQuantity(@Param("orderItemId") Long orderItemId);

    @Select("SELECT COALESCE(quantity,0) FROM shipment_items WHERE shipment_id=#{shipmentId} "
            + "AND order_item_id=#{orderItemId}")
    Integer quantityOrNull(@Param("shipmentId") Long shipmentId, @Param("orderItemId") Long orderItemId);

    default int quantity(Long shipmentId, Long orderItemId) {
        Integer value = quantityOrNull(shipmentId, orderItemId);
        return value == null ? 0 : value;
    }

    @Select("SELECT COALESCE(SUM(ai.quantity),0) FROM after_sale_items ai "
            + "JOIN after_sale_cases c ON c.id=ai.case_id AND c.status='REFUNDED' "
            + "WHERE ai.order_item_id=#{orderItemId} AND ai.source_kind='UNSHIPPED'")
    int refundedUnshippedQuantity(@Param("orderItemId") Long orderItemId);

    @Select("SELECT COALESCE(SUM(i.quantity),0)-COALESCE((SELECT SUM(ai.quantity) "
            + "FROM after_sale_items ai JOIN after_sale_cases c ON c.id=ai.case_id AND c.status='REFUNDED' "
            + "JOIN order_items oi ON oi.id=ai.order_item_id "
            + "WHERE oi.order_id=#{orderId}),0) FROM order_items i WHERE i.order_id=#{orderId}")
    int requiredQuantity(@Param("orderId") Long orderId);

    @Select("SELECT COALESCE((SELECT SUM(si.quantity) FROM shipment_items si "
            + "JOIN shipments s ON s.id=si.shipment_id AND s.status='CONFIRMED' "
            + "WHERE s.order_id=#{orderId}),0)-COALESCE((SELECT SUM(ai.quantity) "
            + "FROM after_sale_items ai JOIN after_sale_cases c ON c.id=ai.case_id AND c.status='REFUNDED' "
            + "JOIN shipments s ON s.id=ai.source_shipment_id AND s.status='CONFIRMED' "
            + "WHERE s.order_id=#{orderId}),0)")
    int confirmedEffectiveQuantity(@Param("orderId") Long orderId);

    @Select("SELECT COALESCE(SUM(si.quantity),0)-COALESCE((SELECT SUM(ai.quantity) "
            + "FROM after_sale_items ai JOIN after_sale_cases c ON c.id=ai.case_id AND c.status='REFUNDED' "
            + "WHERE ai.source_shipment_id=#{shipmentId}),0) "
            + "FROM shipment_items si WHERE si.shipment_id=#{shipmentId}")
    int packageEffectiveQuantity(@Param("shipmentId") Long shipmentId);

    @Select("SELECT MAX(s.confirmed_at) FROM shipments s WHERE s.order_id=#{orderId} "
            + "AND s.status='CONFIRMED' AND EXISTS (SELECT 1 FROM shipment_items si "
            + "WHERE si.shipment_id=s.id AND si.quantity>COALESCE((SELECT SUM(ai.quantity) "
            + "FROM after_sale_items ai JOIN after_sale_cases c ON c.id=ai.case_id AND c.status='REFUNDED' "
            + "WHERE ai.source_shipment_id=s.id AND ai.order_item_id=si.order_item_id),0))")
    LocalDateTime lastEffectiveConfirmation(@Param("orderId") Long orderId);
}
