package com.shiwushop.common.outbox;

import org.apache.ibatis.annotations.Param;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventMapper {
    int insertShipmentAutoConfirm(@Param("shipmentId") Long shipmentId, @Param("now") LocalDateTime now);
    int insertSearchProductRefresh(@Param("productId") Long productId);
    int insertSearchShopRefresh(@Param("shopId") Long shopId);
    int insertSearchBrandRefresh(@Param("brandId") Long brandId);
    int insertSearchCategoryRefresh(@Param("categoryId") Long categoryId);
    List<ShipmentConfirmEvent> selectPendingShipmentEvents(@Param("now") LocalDateTime now, @Param("limit") int limit);
    Long oldestShipmentPendingSeconds(@Param("now") LocalDateTime now);
    ShipmentConfirmEvent selectShipmentEvent(@Param("eventId") Long eventId);
    int claimShipmentEvent(@Param("eventId") Long eventId, @Param("token") String token,
                           @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);
    int markPublished(@Param("eventId") Long eventId, @Param("token") String token, @Param("now") LocalDateTime now);
    int markRetry(@Param("eventId") Long eventId, @Param("token") String token,
                  @Param("nextAttemptAt") LocalDateTime nextAttemptAt);
    int markDead(@Param("eventId") Long eventId, @Param("token") String token);
    SearchEvent selectPendingSearchEvent(@Param("now") LocalDateTime now);
    int claimSearchEvent(@Param("eventId") Long eventId, @Param("token") String token,
                         @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);
    int checkpointSearchEvent(@Param("eventId") Long eventId, @Param("token") String token,
                              @Param("lastProductId") Long lastProductId);
    SearchBacklog selectSearchBacklog();

    @Data
    class SearchEvent {
        private Long id;
        private String eventType;
        private String aggregateType;
        private Long aggregateId;
        private Long lastProductId;
        private int attempts;
    }

    @Data
    class SearchBacklog {
        private long pending;
        private long dead;
        private Long oldestPendingSeconds;
    }

    @Data
    class ShipmentConfirmEvent {
        private Long id;
        private Long shipmentId;
        private int attempts;
    }
}
