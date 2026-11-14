package com.shiwushop.marketing.mapper;

import com.shiwushop.marketing.model.PricingScope;
import com.shiwushop.marketing.model.UserCoupon;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

public interface MarketingPricingMapper {
    @Select("""
            <script>SELECT * FROM user_coupons WHERE user_id=#{user} AND id IN
            <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY id</script>
            """)
    List<UserCoupon> couponMetadata(@Param("user") Long user, @Param("ids") List<Long> ids);

    @Select("""
            <script>SELECT id,activity_id,party,shop_id,budget_amount,held_amount,spent_amount,platform_share_bp,agreed_at,version
            FROM marketing_budgets WHERE activity_id IN
            <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            ORDER BY id <if test='lock'>FOR UPDATE</if></script>
            """)
    List<com.shiwushop.marketing.model.MarketingBudget> budgets(@Param("ids") List<Long> ids,
                                                                @Param("lock") boolean lock);

    @Select("<script>SELECT sku_id FROM marketing_activity_skus WHERE activity_id=#{id} AND scope_role='SALE' ORDER BY sku_id <if test='lock'>FOR SHARE</if></script>")
    List<Long> scopeIds(@Param("id") Long id, @Param("lock") boolean lock);

    @Select("""
            <script>SELECT a.id activity_id,a.activity_code,a.activity_type,a.eligible_level_codes,s.id activity_sku_id,s.sku_id,
            CASE WHEN a.activity_type='MEMBER_PRICE' THEN p.price ELSE s.activity_price END price,
            s.per_user_limit,s.sales_limit,s.reserved_qty,s.paid_qty
            FROM marketing_activities a JOIN marketing_activity_skus s ON s.activity_id=a.id
            LEFT JOIN marketing_member_prices p ON p.activity_sku_id=s.id AND p.level_code=#{level}
            WHERE a.status IN ('APPROVED','RUNNING') AND a.start_at&lt;=#{now} AND a.end_at>#{now}
            AND a.activity_type IN ('MEMBER_PRICE','LIMITED_PRICE') AND s.scope_role='SALE' AND s.sku_id IN
            <foreach collection='skus' item='sku' open='(' separator=',' close=')'>#{sku}</foreach>
            ORDER BY a.id,s.id <if test='lock'>FOR UPDATE</if></script>
            """)
    List<PricingScope> prices(@Param("skus") List<Long> skus, @Param("level") String level,
                              @Param("now") LocalDateTime now, @Param("lock") boolean lock);

    @Select("""
            <script>SELECT c.* FROM user_coupons c
            WHERE c.user_id=#{user} AND c.status='AVAILABLE' AND c.valid_from&lt;=#{now} AND c.expires_at>#{now}
            <if test='selected!=null'>AND c.id IN <foreach collection='selected' item='id' open='(' separator=',' close=')'>#{id}</foreach></if>
            ORDER BY c.id <if test='lock'>FOR UPDATE</if></script>
            """)
    List<UserCoupon> available(@Param("user") Long user, @Param("now") LocalDateTime now, @Param("lock") boolean lock,
                               @Param("selected") List<Long> selected);

    @Select("""
            <script>SELECT reserved_count+consumed_count FROM marketing_user_quotas
            WHERE activity_code=#{code} AND user_id=#{user} AND quota_kind='BUY' AND period_key='ALL'
            <if test='lock'>FOR UPDATE</if></script>
            """)
    Integer usedQuota(@Param("code") String code, @Param("user") Long user, @Param("lock") boolean lock);
}
