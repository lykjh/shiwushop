package com.shiwushop.marketing.mapper;

import com.shiwushop.marketing.model.CouponGrant;
import org.apache.ibatis.annotations.*;

import java.time.*;
import java.util.List;

public interface MonthlyCouponMapper {
    @Select("""
            SELECT MIN(h.effective_at) FROM member_level_changes h JOIN membership_levels l ON l.level_code=h.to_level_code
            WHERE l.monthly_coupon_count>0
            """)
    LocalDateTime firstEligibility();

    @Select("""
            SELECT h.user_id FROM member_level_changes h JOIN membership_levels l ON l.level_code=h.to_level_code
            WHERE l.monthly_coupon_count>0 AND h.effective_at<=#{cutoff}
              AND h.id=(SELECT n.id FROM member_level_changes n WHERE n.user_id=h.user_id AND n.effective_at<=#{cutoff}
                        ORDER BY n.effective_at DESC,n.id DESC LIMIT 1)
              AND NOT EXISTS(SELECT 1 FROM marketing_coupon_grants g WHERE g.user_id=h.user_id
                        AND g.source_kind='MEMBERSHIP' AND g.source_key=#{key})
            ORDER BY h.user_id LIMIT #{size}
            """)
    List<Long> missing(@Param("cutoff") LocalDateTime cutoff, @Param("key") String key, @Param("size") int size);

    @Select("""
            SELECT * FROM marketing_coupon_grants WHERE user_id=#{user} AND source_kind='MEMBERSHIP' AND source_key=#{key}
            """)
    CouponGrant month(@Param("user") long user, @Param("key") String key);

    @Select("""
            SELECT a.id FROM marketing_coupon_rules c JOIN marketing_activities a ON a.id=c.activity_id
            WHERE c.grant_mode='MEMBERSHIP' AND c.member_level_code=#{level} AND a.start_at<=#{at} AND c.claim_end_at>#{at}
              AND a.status IN ('APPROVED','RUNNING','ENDED') ORDER BY a.id LIMIT 2
            """)
    List<Long> templates(@Param("level") String level, @Param("at") LocalDateTime at);

    @Select("""
            SELECT id FROM marketing_coupon_grants WHERE source_kind='MEMBERSHIP' AND status IN ('PENDING','ATTENTION')
              AND (next_attempt_at<=#{now} OR expires_at<=#{now}) AND (claim_until IS NULL OR claim_until<=#{now})
            ORDER BY next_attempt_at,id LIMIT 50
            """)
    List<Long> due(LocalDateTime now);

    @Update("""
            UPDATE marketing_coupon_grants SET claim_token=#{token},claim_until=#{until},activity_id=#{activity}
            WHERE id=#{id} AND status IN ('PENDING','ATTENTION') AND (next_attempt_at<=#{now} OR expires_at<=#{now})
              AND (claim_until IS NULL OR claim_until<=#{now})
            """)
    int claim(@Param("id") long id, @Param("activity") Long activity, @Param("token") String token,
              @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

    @Select("SELECT COUNT(*) FROM marketing_coupon_grants WHERE id=#{id} AND claim_token=#{token} FOR UPDATE")
    int owns(@Param("id") long id, @Param("token") String token);

    @Update("""
            UPDATE marketing_coupon_grants SET status=#{state},error_code=#{error},next_attempt_at=#{next},
              claim_token=NULL,claim_until=NULL WHERE id=#{id} AND claim_token=#{token}
            """)
    int result(@Param("id") long id, @Param("token") String token, @Param("state") String state,
               @Param("error") String error, @Param("next") LocalDateTime next);

    @Update("UPDATE marketing_coupon_grants SET status='PENDING',next_attempt_at=#{now} WHERE id=#{id} AND (status='ATTENTION' OR next_attempt_at>#{now}) AND (claim_until IS NULL OR claim_until<=#{now})")
    int retry(@Param("id") long id, @Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM marketing_coupon_grants
            WHERE (#{user} IS NULL OR user_id=#{user}) AND (#{state} IS NULL OR status=#{state})
              AND (#{kind} IS NULL OR source_kind=#{kind}) AND (#{cursor} IS NULL OR id<#{cursor})
            ORDER BY id DESC LIMIT #{limit}
            """)
    List<CouponGrant> admin(@Param("user") Long user, @Param("state") String state, @Param("kind") String kind,
                            @Param("cursor") Long cursor, @Param("limit") int limit);
}
