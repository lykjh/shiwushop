package com.shiwushop.marketing.mapper;

import com.shiwushop.marketing.model.*;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

public interface MarketingCouponMapper {
    @Select("SELECT c.*,a.name FROM user_coupons c JOIN marketing_activities a ON a.id=c.activity_id WHERE c.grant_id=#{id} ORDER BY c.ordinal_no FOR UPDATE")
    List<UserCoupon> lockGrantCoupons(Long id);

    @Select("SELECT * FROM user_coupons WHERE id=#{id} FOR UPDATE")
    UserCoupon lockCoupon(Long id);

    @Select("""
            SELECT c.id FROM user_coupons c WHERE c.expires_at<=#{now} AND (c.status='AVAILABLE'
                OR (c.status='USED' AND EXISTS (SELECT 1 FROM marketing_budget_holds h WHERE h.source_kind='COUPON'
                    AND h.source_id=c.id AND h.held_amount>0))) ORDER BY c.id LIMIT 100
            """)
    List<Long> expired(LocalDateTime now);

    @Update("UPDATE user_coupons SET status='EXPIRED' WHERE id=#{id}")
    void expire(Long id);

    @Select("SELECT * FROM marketing_coupon_grants WHERE user_id=#{user} AND source_kind=#{kind} AND source_key=#{key} FOR UPDATE")
    CouponGrant source(@Param("user") Long user, @Param("kind") String kind, @Param("key") String key);

    @Select("SELECT * FROM marketing_coupon_grants WHERE actor_user_id=#{actor} AND activity_id=#{activity} AND source_key=#{key} FOR UPDATE")
    CouponGrant compensation(@Param("actor") Long actor, @Param("activity") Long activity, @Param("key") String key);

    @Insert("""
            INSERT INTO marketing_coupon_grants(user_id,activity_id,source_kind,source_key,benefit_month,eligible_level_code,
              entitled_count,face_amount,threshold_amount,expires_at,status,next_attempt_at,code_id,request_hash,actor_user_id,reason,evidence_reference)
            VALUES(#{userId},#{activityId},#{sourceKind},#{sourceKey},#{benefitMonth},#{eligibleLevelCode},#{entitledCount},
              #{faceAmount},#{thresholdAmount},#{expiresAt},#{status},#{nextAttemptAt},#{codeId},#{requestHash},#{actorUserId},#{reason},#{evidenceReference})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertGrant(CouponGrant grant);

    @Select("SELECT * FROM marketing_coupon_grants WHERE id=#{id} FOR UPDATE")
    CouponGrant lockGrant(Long id);

    @Update("UPDATE marketing_coupon_grants SET status='GRANTED',error_code=NULL,claim_token=NULL,claim_until=NULL WHERE id=#{id}")
    void granted(Long id);

    @Update("UPDATE marketing_coupon_rules SET issued_count=issued_count+#{count} WHERE activity_id=#{activity} AND issued_count+#{count}<=issue_limit")
    int issue(@Param("activity") Long activity, @Param("count") int count);

    @Insert("""
            INSERT INTO marketing_user_quotas(activity_id,activity_code,user_id,quota_kind,period_key)
            VALUES(#{activity},#{code},#{user},'COUPON_CLAIM','ALL') ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)
            """)
    void initializeQuota(@Param("activity") Long activity, @Param("code") String code, @Param("user") Long user);

    @Update("""
            UPDATE marketing_user_quotas SET activity_id=#{activity},consumed_count=consumed_count+#{count},version=version+1
            WHERE activity_code=#{code} AND user_id=#{user} AND quota_kind='COUPON_CLAIM' AND period_key='ALL'
            AND consumed_count+#{count}<=#{limit}
            """)
    int consumeQuota(@Param("activity") Long activity, @Param("code") String code, @Param("user") Long user,
                     @Param("count") int count, @Param("limit") int limit);

    @Insert("""
            INSERT INTO user_coupons(grant_id,ordinal_no,user_id,activity_id,face_amount,threshold_amount,valid_from,expires_at,status)
            VALUES(#{grantId},#{ordinalNo},#{userId},#{activityId},#{faceAmount},#{thresholdAmount},#{validFrom},#{expiresAt},'AVAILABLE')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertCoupon(UserCoupon coupon);

    @Select("SELECT c.*,a.name FROM user_coupons c JOIN marketing_activities a ON a.id=c.activity_id WHERE c.grant_id=#{id} ORDER BY c.ordinal_no")
    List<UserCoupon> grantCoupons(Long id);

    @Select("""
            <script>SELECT c.*,a.name FROM user_coupons c JOIN marketing_activities a ON a.id=c.activity_id WHERE c.user_id=#{user}
            <if test='afterId!=null'>AND c.id &lt; #{afterId}</if>
            <if test='status!=null'>AND (CASE WHEN c.status='AVAILABLE' AND c.expires_at&lt;=#{now} THEN 'EXPIRED' ELSE c.status END)=#{status}</if>
            <if test='shop!=null'>AND EXISTS(SELECT 1 FROM marketing_budgets b WHERE b.activity_id=c.activity_id AND b.shop_id=#{shop})</if>
            ORDER BY c.id DESC LIMIT #{limit}</script>
            """)
    List<UserCoupon> coupons(@Param("user") Long user, @Param("afterId") Long afterId, @Param("status") String status,
                             @Param("shop") Long shop, @Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("""
            <script>SELECT * FROM marketing_coupon_grants WHERE user_id=#{user}
            <if test='afterId!=null'>AND id &lt; #{afterId}</if><if test='kind!=null'>AND source_kind=#{kind}</if>
            ORDER BY id DESC LIMIT #{limit}</script>
            """)
    List<CouponGrant> grants(@Param("user") Long user, @Param("afterId") Long afterId, @Param("kind") String kind,
                             @Param("limit") int limit);

    @Select("SELECT * FROM marketing_coupon_codes WHERE code_hash=#{hash}")
    CouponCode code(byte[] hash);

    @Update("""
            UPDATE marketing_coupon_codes SET redeemed_count=redeemed_count+1
            WHERE id=#{id} AND status='ACTIVE' AND expires_at>#{now} AND redeemed_count<max_redemptions
            """)
    int consumeCode(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM marketing_coupon_codes WHERE id=#{id} FOR UPDATE")
    CouponCode lockCode(Long id);

    @Select("SELECT * FROM marketing_coupon_codes WHERE creator_user_id=#{actor} AND activity_id=#{activity} AND creation_request_key=#{key} ORDER BY creation_ordinal FOR UPDATE")
    List<CouponCode> batch(@Param("actor") Long actor, @Param("activity") Long activity, @Param("key") String key);

    @Insert("""
            INSERT INTO marketing_coupon_codes(activity_id,code_hash,code_kind,max_redemptions,redeemed_count,expires_at,
              status,creator_user_id,creation_request_key,creation_request_hash,creation_ordinal)
            VALUES(#{activityId},#{codeHash},#{codeKind},#{maxRedemptions},0,#{expiresAt},'ACTIVE',#{creatorUserId},
              #{creationRequestKey},#{creationRequestHash},#{creationOrdinal})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertCode(CouponCode code);

    @Update("UPDATE marketing_coupon_codes SET redeemed_count=redeemed_count+1 WHERE id=#{id}")
    void redeem(Long id);

    @Update("UPDATE marketing_coupon_codes SET status='DISABLED' WHERE id=#{id}")
    void disable(Long id);

    @Select("""
            <script>SELECT * FROM marketing_coupon_codes WHERE activity_id=#{activity}
            <if test='afterId!=null'>AND id &lt; #{afterId}</if><if test='status!=null'>AND status=#{status}</if>
            ORDER BY id DESC LIMIT #{limit}</script>
            """)
    List<CouponCode> codes(@Param("activity") Long activity, @Param("afterId") Long afterId,
                           @Param("status") String status, @Param("limit") int limit);

    @Insert("""
            INSERT INTO audit_events(actor_user_id,action,target_type,target_id,summary,occurred_at)
            VALUES(#{actor},#{action},#{type},#{id},#{summary},#{now})
            """)
    void audit(@Param("actor") Long actor, @Param("action") String action, @Param("type") String type,
               @Param("id") Long id, @Param("summary") String summary, @Param("now") LocalDateTime now);
}
