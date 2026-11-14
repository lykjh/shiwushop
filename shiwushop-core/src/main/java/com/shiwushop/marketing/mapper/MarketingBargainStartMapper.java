package com.shiwushop.marketing.mapper;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface MarketingBargainStartMapper {
    record Scope(Long activityId, String activityCode, Long skuId, String status,
                 LocalDateTime startAt, LocalDateTime endAt, String eligibleLevelCodes,
                 BigDecimal bargainStartPrice, BigDecimal bargainTargetPrice, int requiredHelpers,
                 int participationSeconds, int maxOpenSessions) {
    }

    record Participation(Long id, Long activitySkuId, byte[] requestHash) {
    }

    @Select("SELECT sku_id FROM marketing_activity_skus WHERE id=#{scope}")
    Long skuId(long scope);

    @Select("""
            SELECT s.activity_id,a.activity_code,s.sku_id,a.status,a.start_at,a.end_at,a.eligible_level_codes,
              s.bargain_start_price,s.bargain_target_price,s.required_helpers,s.participation_seconds,
              s.max_open_sessions
            FROM marketing_activity_skus s JOIN marketing_activities a ON a.id=s.activity_id
            WHERE s.id=#{scope} AND a.activity_type='BARGAIN' AND s.scope_role='SALE' FOR UPDATE
            """)
    Scope lockScope(long scope);

    @Select("SELECT id,activity_sku_id,request_hash FROM marketing_participations " +
            "WHERE user_id=#{user} AND request_key=#{key}")
    Participation participation(@Param("user") long user, @Param("key") String key);

    @Select("SELECT id FROM marketing_bargains WHERE participation_id=#{participation}")
    Long bargainId(long participation);

    @Select("SELECT COUNT(*) FROM marketing_bargains WHERE activity_sku_id=#{scope} " +
            "AND status IN ('ACTIVE','QUALIFIED')")
    int openCount(long scope);

    @Insert("""
            INSERT INTO marketing_participations(activity_sku_id,user_id,request_key,request_hash,quantity,
              status,expires_at)
            VALUES(#{scope},#{user},#{key},#{hash},1,'RESERVED',#{expires})
            """)
    void insertParticipation(@Param("scope") long scope, @Param("user") long user,
                             @Param("key") String key, @Param("hash") byte[] hash,
                             @Param("expires") LocalDateTime expires);

    @Select("SELECT LAST_INSERT_ID()")
    Long lastId();

    @Insert("""
            INSERT INTO marketing_user_quotas(activity_id,activity_code,user_id,quota_kind,period_key)
            VALUES(#{activity},#{code},#{user},'OPEN_BARGAIN','ALL')
            ON DUPLICATE KEY UPDATE id=id
            """)
    void ensureOpenQuota(@Param("activity") long activity, @Param("code") String code,
                         @Param("user") long user);

    @Update("""
            UPDATE marketing_user_quotas SET reserved_count=reserved_count+1,version=version+1
            WHERE activity_code=#{code} AND user_id=#{user} AND quota_kind='OPEN_BARGAIN'
              AND period_key='ALL' AND reserved_count<1
            """)
    int reserveOpenQuota(@Param("code") String code, @Param("user") long user);

    @Insert("""
            INSERT INTO marketing_bargains(participation_id,activity_sku_id,user_id,start_amount,target_amount,
              current_amount,required_helpers,status,help_expires_at)
            VALUES(#{participation},#{scope},#{user},#{start},#{target},#{start},#{helpers},'ACTIVE',#{expires})
            """)
    void insertBargain(@Param("participation") long participation, @Param("scope") long scope,
                       @Param("user") long user, @Param("start") BigDecimal start,
                       @Param("target") BigDecimal target, @Param("helpers") int helpers,
                       @Param("expires") LocalDateTime expires);
}
