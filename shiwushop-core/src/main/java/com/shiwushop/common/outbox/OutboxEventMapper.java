package com.shiwushop.common.outbox;

import org.apache.ibatis.annotations.Param;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventMapper {
    int insertShipmentAutoConfirm(@Param("shipmentId") Long shipmentId,
                                  @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    int insertSearchProductRefresh(@Param("productId") Long productId);

    int insertSearchShopRefresh(@Param("shopId") Long shopId);

    int insertSearchBrandRefresh(@Param("brandId") Long brandId);

    int insertSearchCategoryRefresh(@Param("categoryId") Long categoryId);

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

    SearchEvent selectPendingSearchEvent(@Param("now") LocalDateTime now);

    int claimSearchEvent(@Param("eventId") Long eventId, @Param("token") String token,
                         @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

    int checkpointSearchEvent(@Param("eventId") Long eventId, @Param("token") String token,
                              @Param("lastProductId") Long lastProductId);

    SearchBacklog selectSearchBacklog();

    @Data
    class SearchEvent {
        /** 搜索刷新 Outbox 事件 ID。 */
        private Long id;
        /** Outbox 事件类型。 */
        private String eventType;
        /** 待刷新业务聚合的类型。 */
        private String aggregateType;
        /** 待刷新的业务聚合 ID，与 aggregateType 配合定位。 */
        private Long aggregateId;
        /** 批量刷新最近完成的商品 ID 检查点。 */
        private Long lastProductId;
        /** 该事件已尝试发布的次数。 */
        private int attempts;
    }

    @Data
    class SearchBacklog {
        /** 当前待处理的搜索事件数。 */
        private long pending;
        /** 当前进入人工处理的搜索死信事件数。 */
        private long dead;
        /** 最早待处理搜索事件已等待的秒数。 */
        private Long oldestPendingSeconds;
    }

    @Data
    class ShipmentConfirmEvent {
        /** 自动收货 Outbox 事件 ID，重试保留原 ID。 */
        private Long id;
        /** 关联发货包裹 ID。 */
        private Long shipmentId;
        /** 该事件已尝试发布的次数。 */
        private int attempts;
    }
}
