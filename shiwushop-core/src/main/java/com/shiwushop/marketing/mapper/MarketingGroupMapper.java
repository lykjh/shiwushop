package com.shiwushop.marketing.mapper;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

public interface MarketingGroupMapper {
    @Select("SELECT id FROM marketing_groups WHERE status='FORMING' AND expires_at<=#{now} ORDER BY id LIMIT 100")
    List<Long> due(LocalDateTime now);

    @Select("""
            SELECT p.shop_id FROM marketing_groups g JOIN marketing_activity_skus s ON s.id=g.activity_sku_id
            JOIN skus k ON k.id=s.sku_id JOIN products p ON p.id=k.product_id WHERE g.id=#{id}
            """)
    Long shopId(long id);

    @Update("UPDATE marketing_groups SET status='FAILED',version=version+1 " +
            "WHERE id=#{id} AND status='FORMING' AND expires_at<=#{now}")
    int fail(@Param("id") long id, @Param("now") LocalDateTime now);

    record GroupRow(Long id, Long activityId, Long activitySkuId, Long skuId, String status,
                    int requiredMembers, int reservedMembers, int paidMembers,
                    LocalDateTime openedAt, LocalDateTime expiresAt, LocalDateTime succeededAt) {
    }

    @Select("""
            <script>SELECT g.id,s.activity_id,g.activity_sku_id,s.sku_id,g.status,g.required_members,
              g.reserved_members,g.paid_members,g.opened_at,g.expires_at,g.succeeded_at
            FROM marketing_groups g JOIN marketing_activity_skus s ON s.id=g.activity_sku_id
            JOIN marketing_activities a ON a.id=s.activity_id
            JOIN skus k ON k.id=s.sku_id JOIN products p ON p.id=k.product_id
            JOIN shops sh ON sh.id=p.shop_id
            JOIN marketing_stock_pools pool ON pool.activity_sku_id=s.id
            WHERE s.activity_id=#{activity} AND s.id=#{scope} AND a.activity_type='GROUP'
              AND a.status='RUNNING' AND a.start_at&lt;=#{now} AND a.end_at&gt;#{now}
              AND g.status='FORMING' AND g.opened_at IS NOT NULL AND g.expires_at&gt;#{now}
              AND g.reserved_members+g.paid_members&lt;g.required_members
              AND pool.available_qty&gt;0 AND k.status='ACTIVE' AND p.status='ON_SALE' AND sh.enabled=1
            <if test='afterId!=null'>AND g.id&lt;#{afterId}</if>
            ORDER BY g.id DESC LIMIT #{limit}</script>
            """)
    List<GroupRow> available(@Param("activity") long activity, @Param("scope") long scope,
                             @Param("afterId") Long afterId, @Param("limit") int limit,
                             @Param("now") LocalDateTime now);

    @Select("""
            SELECT g.id,s.activity_id,g.activity_sku_id,s.sku_id,g.status,g.required_members,
              g.reserved_members,g.paid_members,g.opened_at,g.expires_at,g.succeeded_at
            FROM marketing_groups g JOIN marketing_activity_skus s ON s.id=g.activity_sku_id
            JOIN marketing_activities a ON a.id=s.activity_id
            WHERE g.id=#{id} AND a.activity_type='GROUP' AND g.opened_at IS NOT NULL
            """)
    GroupRow publicGroup(long id);
}
