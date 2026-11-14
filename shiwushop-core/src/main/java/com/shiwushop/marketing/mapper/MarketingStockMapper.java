package com.shiwushop.marketing.mapper;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

public interface MarketingStockMapper {
    record Scope(Long activityId, Long skuId, String status) {
    }

    record Balance(Long id, Long activitySkuId, int allocatedQty, int availableQty, int heldQty,
                   int consumedQty, int returnedToNormalQty) {
    }

    record TransferEntry(Long id, Long poolId, Long normalMovementId, Long actorUserId, String reason,
                         String operationReason, int allocatedDelta, int availableDelta, int returnedDelta) {
    }

    record PoolRow(Long id, Long activitySkuId, Long skuId, int allocatedQty, int availableQty,
                   int heldQty, int consumedQty, int returnedToNormalQty) {
    }

    record EntryRow(Long id, String reason, int allocatedDelta, int availableDelta, int heldDelta,
                    int consumedDelta, int returnedDelta, Long orderItemId, Long participationId,
                    LocalDateTime createdAt) {
    }

    @Select("""
            <script>SELECT COUNT(*) FROM marketing_activity_skus s JOIN skus k ON k.id=s.sku_id
            JOIN products p ON p.id=k.product_id WHERE s.activity_id=#{activity}
            <if test='shop!=null'>AND p.shop_id=#{shop}</if></script>
            """)
    int scopeCount(@Param("activity") long activity, @Param("shop") Long shop);

    @Select("""
            <script>SELECT p.id,p.activity_sku_id,s.sku_id,p.allocated_qty,p.available_qty,p.held_qty,
              p.consumed_qty,p.returned_to_normal_qty
            FROM marketing_activity_skus s JOIN skus k ON k.id=s.sku_id
            JOIN products product ON product.id=k.product_id
            JOIN marketing_stock_pools p ON p.activity_sku_id=s.id
            WHERE s.activity_id=#{activity}
            <if test='shop!=null'>AND product.shop_id=#{shop}</if>
            ORDER BY s.id</script>
            """)
    List<PoolRow> pools(@Param("activity") long activity, @Param("shop") Long shop);

    @Select("""
            SELECT e.id,e.reason,e.allocated_delta,e.available_delta,e.held_delta,e.consumed_delta,
              e.returned_delta,e.order_item_id,e.participation_id,e.created_at
            FROM marketing_stock_entries e JOIN marketing_stock_pools p ON p.id=e.pool_id
            WHERE p.activity_sku_id=#{scope} AND (#{afterId} IS NULL OR e.id<#{afterId})
            ORDER BY e.id DESC LIMIT #{limit}
            """)
    List<EntryRow> entries(@Param("scope") long scope, @Param("afterId") Long afterId,
                           @Param("limit") int limit);

    @Select("SELECT s.activity_id,s.sku_id,a.status FROM marketing_activity_skus s " +
            "JOIN marketing_activities a ON a.id=s.activity_id WHERE s.id=#{id}")
    Scope scope(long id);

    @Select("SELECT s.activity_id,s.sku_id,a.status FROM marketing_activity_skus s " +
            "JOIN marketing_activities a ON a.id=s.activity_id WHERE s.id=#{id} FOR UPDATE")
    Scope lockScope(long id);

    @Insert("INSERT INTO marketing_stock_pools(activity_sku_id) VALUES(#{scope}) " +
            "ON DUPLICATE KEY UPDATE id=id")
    void ensurePool(long scope);

    @Select("SELECT id,activity_sku_id,allocated_qty,available_qty,held_qty,consumed_qty,returned_to_normal_qty " +
            "FROM marketing_stock_pools WHERE activity_sku_id=#{scope} FOR UPDATE")
    Balance lockBalance(long scope);

    @Select("SELECT id,pool_id,normal_movement_id,actor_user_id,reason,operation_reason," +
            "allocated_delta,available_delta,returned_delta FROM marketing_stock_entries WHERE operation_key=#{key}")
    TransferEntry transferEntry(String key);

    @Update("""
            UPDATE marketing_stock_pools SET allocated_qty=allocated_qty+#{allocated},
              available_qty=available_qty+#{available},returned_to_normal_qty=returned_to_normal_qty+#{returned},
              version=version+1 WHERE id=#{pool} AND available_qty+#{available}>=0
            """)
    int transfer(@Param("pool") long pool, @Param("allocated") int allocated,
                 @Param("available") int available, @Param("returned") int returned);

    @Insert("""
            INSERT INTO marketing_stock_entries(pool_id,operation_key,reason,allocated_delta,available_delta,
              returned_delta,normal_movement_id,actor_user_id,operation_reason)
            VALUES(#{pool},#{key},#{reason},#{allocated},#{available},#{returned},#{movement},#{actor},#{detail})
            """)
    void recordTransfer(@Param("pool") long pool, @Param("key") String key, @Param("reason") String reason,
                        @Param("allocated") int allocated, @Param("available") int available,
                        @Param("returned") int returned, @Param("movement") long movement,
                        @Param("actor") long actor, @Param("detail") String detail);

    record Pool(Long id, Long activitySkuId) {
    }

    record Entry(Long poolId, Long participationId, String reason, int availableDelta, int heldDelta,
                 int consumedDelta) {
    }

    @Select("SELECT id,activity_sku_id FROM marketing_stock_pools WHERE activity_sku_id=#{scope} FOR UPDATE")
    Pool lockPool(long scope);

    @Select("SELECT pool_id,participation_id,reason,available_delta,held_delta,consumed_delta " +
            "FROM marketing_stock_entries WHERE operation_key=#{key}")
    Entry entry(String key);

    @Update("""
            UPDATE marketing_stock_pools SET available_qty=available_qty+#{available},
              held_qty=held_qty+#{held},consumed_qty=consumed_qty+#{consumed},version=version+1
            WHERE id=#{pool} AND available_qty+#{available}>=0 AND held_qty+#{held}>=0
              AND consumed_qty+#{consumed}>=0
            """)
    int change(@Param("pool") long pool, @Param("available") int available, @Param("held") int held,
               @Param("consumed") int consumed);

    @Insert("""
            INSERT INTO marketing_stock_entries(pool_id,operation_key,reason,available_delta,held_delta,
              consumed_delta,participation_id)
            VALUES(#{pool},#{key},#{reason},#{available},#{held},#{consumed},#{participation})
            """)
    void record(@Param("pool") long pool, @Param("key") String key, @Param("reason") String reason,
                @Param("available") int available, @Param("held") int held, @Param("consumed") int consumed,
                @Param("participation") long participation);
}
