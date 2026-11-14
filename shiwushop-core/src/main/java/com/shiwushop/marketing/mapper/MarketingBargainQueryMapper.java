package com.shiwushop.marketing.mapper;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface MarketingBargainQueryMapper {
    record BargainRow(Long id, Long activityId, Long activitySkuId, Long skuId, Long participationId,
                      Long orderId, String status, BigDecimal startAmount, BigDecimal targetAmount,
                      BigDecimal currentAmount, int requiredHelpers, int helpCount,
                      LocalDateTime helpExpiresAt, LocalDateTime purchaseExpiresAt,
                      String skuStatus, String productStatus, boolean shopEnabled) {
    }

    /** 砍价查询的投影字段，包含商品、SKU 与店铺的可用状态。 */
    String COLUMNS = "b.id,s.activity_id,b.activity_sku_id,s.sku_id,b.participation_id,p.order_id," +
            "b.status,b.start_amount,b.target_amount,b.current_amount,b.required_helpers,b.help_count," +
            "b.help_expires_at,b.purchase_expires_at,k.status sku_status,product.status product_status," +
            "sh.enabled shop_enabled";
    /** 砍价查询共用关联关系，同时核对参与记录的用户及活动 SKU。 */
    String JOINS = " FROM marketing_bargains b JOIN marketing_activity_skus s ON s.id=b.activity_sku_id" +
            " JOIN marketing_participations p ON p.id=b.participation_id AND p.user_id=b.user_id" +
            " AND p.activity_sku_id=b.activity_sku_id" +
            " JOIN skus k ON k.id=s.sku_id JOIN products product ON product.id=k.product_id" +
            " JOIN shops sh ON sh.id=product.shop_id";

    @Select("SELECT " + COLUMNS + JOINS + " WHERE b.id=#{id}")
    BargainRow find(long id);

    @Select("SELECT " + COLUMNS + JOINS + " WHERE b.id=#{id} AND b.user_id=#{user}")
    BargainRow findMine(@Param("id") long id, @Param("user") long user);

    @Select("""
            <script>SELECT
            """ + COLUMNS + JOINS + """
             WHERE b.user_id=#{user}
            <if test='status!=null'>AND b.status=#{status}</if>
            <if test='afterId!=null'>AND b.id&lt;#{afterId}</if>
            ORDER BY b.id DESC LIMIT #{limit}</script>
            """)
    List<BargainRow> mine(@Param("user") long user, @Param("status") String status,
                          @Param("afterId") Long afterId, @Param("limit") int limit);
}
