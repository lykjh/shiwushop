package com.shiwushop.marketing.mapper;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

public interface MarketingPointProductMapper {
    record PointProductRow(Long activityId, Long activitySkuId, Long skuId, String productName,
                           Long coverMediaId, Long shopId, long pointCostUnits, Integer perUserLimit,
                           int availableQuantity, LocalDateTime startAt, LocalDateTime endAt) {
    }

    @Select("""
            <script>SELECT a.id activity_id,s.id activity_sku_id,s.sku_id,p.name product_name,
              image.media_id cover_media_id,p.shop_id,s.point_cost_units,s.per_user_limit,
              pool.available_qty available_quantity,a.start_at,a.end_at
            FROM marketing_activity_skus s JOIN marketing_activities a ON a.id=s.activity_id
            JOIN skus k ON k.id=s.sku_id JOIN products p ON p.id=k.product_id
            JOIN shops sh ON sh.id=p.shop_id
            JOIN marketing_stock_pools pool ON pool.activity_sku_id=s.id
            LEFT JOIN product_images image ON image.product_id=p.id AND image.is_cover=1
            WHERE a.activity_type='POINTS' AND a.status='RUNNING' AND a.start_at&lt;=#{now}
              AND a.end_at&gt;#{now} AND s.scope_role='REDEEM' AND s.point_cost_units&gt;0
              AND pool.available_qty&gt;0 AND k.status='ACTIVE' AND p.status='ON_SALE' AND sh.enabled=1
            <if test='shop!=null'>AND p.shop_id=#{shop}</if>
            <if test='afterId!=null'>AND s.id&lt;#{afterId}</if>
            ORDER BY s.id DESC LIMIT #{limit}</script>
            """)
    List<PointProductRow> available(@Param("shop") Long shop, @Param("afterId") Long afterId,
                                    @Param("limit") int limit, @Param("now") LocalDateTime now);
}
