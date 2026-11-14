package com.shiwushop.marketing.mapper;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

public interface MarketingBargainExpiryMapper {
    record Participation(String status, Long orderId) {
    }

    record Scope(Long skuId, String activityCode) {
    }

    @Select("""
            SELECT id FROM marketing_bargains WHERE
              (status='ACTIVE' AND help_expires_at<=#{now})
              OR (status='QUALIFIED' AND purchase_expires_at<=#{now})
            ORDER BY id LIMIT 100
            """)
    List<Long> due(LocalDateTime now);

    @Select("SELECT status,order_id FROM marketing_participations WHERE id=#{id} FOR UPDATE")
    Participation lockParticipation(long id);

    @Select("""
            SELECT s.sku_id,a.activity_code FROM marketing_activity_skus s
            JOIN marketing_activities a ON a.id=s.activity_id WHERE s.id=#{scope}
            """)
    Scope scope(long scope);

    @Update("UPDATE marketing_bargains SET status='EXPIRED',version=version+1 " +
            "WHERE id=#{id} AND status=#{previous}")
    int expire(@Param("id") long id, @Param("previous") String previous);

    @Update("UPDATE marketing_participations SET status='RELEASED' " +
            "WHERE id=#{id} AND order_id IS NULL AND status=#{previous}")
    int releaseParticipation(@Param("id") long id, @Param("previous") String previous);

    @Update("""
            UPDATE marketing_user_quotas SET reserved_count=reserved_count-1,version=version+1
            WHERE activity_code=#{code} AND user_id=#{user} AND quota_kind='OPEN_BARGAIN'
              AND period_key='ALL' AND reserved_count>=1
            """)
    int releaseOpenQuota(@Param("code") String code, @Param("user") long user);
}
