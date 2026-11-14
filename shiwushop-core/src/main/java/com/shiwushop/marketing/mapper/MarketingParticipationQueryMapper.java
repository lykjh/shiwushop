package com.shiwushop.marketing.mapper;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

public interface MarketingParticipationQueryMapper {
    record ParticipationRow(Long id, String type, Long activityId, Long activitySkuId, Long orderId,
                            Long groupId, Long bargainId, int quantity, String status,
                            LocalDateTime expiresAt, LocalDateTime paidAt) {
    }

    record RedemptionRow(Long orderId, Long participationId, Long activitySkuId, long spentPointUnits,
                         long returnedPointUnits, String status, LocalDateTime createdAt) {
    }

    @Select("""
            <script>SELECT p.id,a.activity_type type,s.activity_id,p.activity_sku_id,p.order_id,p.group_id,
              b.id bargain_id,p.quantity,p.status,p.expires_at,p.paid_at
            FROM marketing_participations p JOIN marketing_activity_skus s ON s.id=p.activity_sku_id
            JOIN marketing_activities a ON a.id=s.activity_id
            LEFT JOIN marketing_bargains b ON b.participation_id=p.id
            WHERE p.user_id=#{user}
            <if test='type!=null'>AND a.activity_type=#{type}</if>
            <if test='status!=null'>AND p.status=#{status}</if>
            <if test='afterId!=null'>AND p.id&lt;#{afterId}</if>
            ORDER BY p.id DESC LIMIT #{limit}</script>
            """)
    List<ParticipationRow> participations(@Param("user") long user, @Param("type") String type,
                                          @Param("status") String status, @Param("afterId") Long afterId,
                                          @Param("limit") int limit);

    @Select("""
            <script>SELECT order_id,participation_id,activity_sku_id,spent_point_units,returned_point_units,
              status,created_at FROM point_redemptions WHERE user_id=#{user}
            <if test='status!=null'>AND status=#{status}</if>
            <if test='afterId!=null'>AND order_id&lt;#{afterId}</if>
            ORDER BY order_id DESC LIMIT #{limit}</script>
            """)
    List<RedemptionRow> redemptions(@Param("user") long user, @Param("status") String status,
                                    @Param("afterId") Long afterId, @Param("limit") int limit);
}
