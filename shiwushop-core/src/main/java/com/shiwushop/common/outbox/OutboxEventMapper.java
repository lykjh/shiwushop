package com.shiwushop.common.outbox;

import org.apache.ibatis.annotations.Param;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventMapper {
    int insertShipmentAutoConfirm(@Param("shipmentId") Long shipmentId,
                                  @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    List<ShipmentConfirmEvent> selectPendingShipmentEvents(@Param("now") LocalDateTime now, @Param("limit") int limit);

    Long oldestShipmentPendingSeconds(@Param("now") LocalDateTime now);

    long futureShipmentCount(@Param("now") LocalDateTime now);

    long eligibleShipmentCount(@Param("now") LocalDateTime now);

    int deferShipmentEvent(@Param("eventId") Long eventId, @Param("token") String token,
                           @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    ShipmentConfirmEvent selectShipmentEvent(@Param("eventId") Long eventId);

    int claimShipmentEvent(@Param("eventId") Long eventId, @Param("token") String token,
                           @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

    int markPublished(@Param("eventId") Long eventId, @Param("token") String token, @Param("now") LocalDateTime now);

    int markRetry(@Param("eventId") Long eventId, @Param("token") String token,
                  @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    int markDead(@Param("eventId") Long eventId, @Param("token") String token);

    @Data
    class ShipmentConfirmEvent {
        /** 自动收货 Outbox 事件 ID，重试保留原 ID。 */
        private Long id;
        /** 关联发货包裹 ID。 */
        private Long shipmentId;
        private boolean retired;
        /** 该事件已尝试发布的次数。 */
        private int attempts;
    }
}
