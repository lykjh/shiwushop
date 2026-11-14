package com.shiwushop.trade.mapper.shipment;

import com.shiwushop.trade.entity.shipment.ShipmentCorrection;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.shiwushop.trade.dto.shipment.correction.ShipmentCorrectionStatus;
import com.shiwushop.trade.vo.shipment.correction.ShipmentCorrectionHistoryVO;

import java.time.LocalDateTime;
import java.util.List;

public interface ShipmentCorrectionMapper {
    @Select("SELECT correction_version FROM shipments WHERE id=#{id}")
    int version(@Param("id") Long id);

    @Select("SELECT * FROM shipment_corrections WHERE shipment_id=#{shipmentId} AND status='PENDING' FOR UPDATE")
    ShipmentCorrection pendingForUpdate(@Param("shipmentId") Long shipmentId);

    @Insert("""
            INSERT INTO shipment_corrections(shipment_id,order_id,shop_id,original_version,
                old_carrier_name,old_tracking_no,new_carrier_name,new_tracking_no,reason,requested_by,requested_at,status)
            VALUES (#{shipmentId},#{orderId},#{shopId},#{originalVersion},#{oldCarrierName},#{oldTrackingNo},
                #{newCarrierName},#{newTrackingNo},#{reason},#{requestedBy},#{requestedAt},'PENDING')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ShipmentCorrection correction);

    @Select("SELECT * FROM shipment_corrections WHERE id=#{id}")
    ShipmentCorrection find(@Param("id") Long id);

    @Select("SELECT * FROM shipment_corrections WHERE id=#{id} FOR UPDATE")
    ShipmentCorrection findForUpdate(@Param("id") Long id);

    /** 仅在原版本匹配且仍已发货时替换承运商和单号。 */
    @Update("""
            UPDATE shipments SET carrier_name=#{carrier}, tracking_no=#{tracking}, simulated_in_transit_at=NULL,
                correction_version=correction_version+1
            WHERE id=#{id} AND correction_version=#{version} AND status='SHIPPED' AND carrier_signed_at IS NULL
            """)
    int apply(@Param("id") Long id, @Param("version") int version,
              @Param("carrier") String carrier, @Param("tracking") String tracking);

    @Update("UPDATE shipment_corrections SET status=#{status}, decided_by=#{userId}, decided_at=#{now}, remark=#{remark} WHERE id=#{id} AND status='PENDING'")
    int decide(@Param("id") Long id, @Param("status") String status, @Param("userId") Long userId,
               @Param("now") LocalDateTime now, @Param("remark") String remark);

    @Select("""
            SELECT id, original_version, old_carrier_name, old_tracking_no, new_carrier_name,
                   new_tracking_no, reason, requested_by, requested_at, decided_by, decided_at
            FROM shipment_corrections WHERE order_id=#{orderId} AND status='APPROVED' ORDER BY id
            """)
    List<ShipmentCorrectionHistoryVO> history(@Param("orderId") Long orderId);

    @Select("""
            SELECT id, original_version, old_carrier_name, old_tracking_no, new_carrier_name,
                   new_tracking_no, reason, requested_by, requested_at, decided_by, decided_at
            FROM shipment_corrections WHERE shipment_id=#{shipmentId} AND status='APPROVED' ORDER BY id
            """)
    List<ShipmentCorrectionHistoryVO> historyForShipment(@Param("shipmentId") Long shipmentId);

    @Select("""
            <script>
            SELECT * FROM shipment_corrections WHERE status=#{status}
            <if test="afterId != null">AND id &lt; #{afterId}</if>
            ORDER BY id DESC LIMIT #{limit}
            </script>
            """)
    List<ShipmentCorrection> list(@Param("status") ShipmentCorrectionStatus status,
                                  @Param("afterId") Long afterId, @Param("limit") int limit);
}
