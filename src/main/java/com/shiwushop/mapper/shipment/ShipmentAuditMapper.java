package com.shiwushop.mapper.shipment;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface ShipmentAuditMapper {
    @Insert("""
            INSERT INTO audit_events (actor_user_id, action, target_type, target_id, summary, occurred_at)
            VALUES (#{actorUserId}, #{action}, 'SHIPMENT', #{shipmentId}, #{summary}, #{now})
            """)
    int record(@Param("actorUserId") Long actorUserId, @Param("action") String action,
               @Param("shipmentId") Long shipmentId, @Param("summary") String summary,
               @Param("now") LocalDateTime now);
}
