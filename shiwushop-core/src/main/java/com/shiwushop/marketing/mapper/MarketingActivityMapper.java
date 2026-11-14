package com.shiwushop.marketing.mapper;

import com.shiwushop.marketing.dto.*;
import com.shiwushop.marketing.model.*;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface MarketingActivityMapper {
    @Select("""
            SELECT a.id FROM marketing_activities a
            JOIN marketing_activity_skus s ON s.activity_id=a.id
            JOIN skus k ON k.id=s.sku_id JOIN products p ON p.id=k.product_id
            WHERE p.shop_id=#{shopId} AND p.id=#{productId}
              AND a.status IN ('APPROVED','RUNNING','PAUSED')
            ORDER BY a.id LIMIT 1 FOR UPDATE
            """)
    Long activeProductActivity(@Param("shopId") Long shopId, @Param("productId") Long productId);

    @Select("""
            SELECT x.id FROM marketing_participations x
            JOIN marketing_activity_skus s ON s.id=x.activity_sku_id
            JOIN skus k ON k.id=s.sku_id JOIN products p ON p.id=k.product_id
            LEFT JOIN marketing_groups g ON g.id=x.group_id
            LEFT JOIN marketing_bargains b ON b.participation_id=x.id
            LEFT JOIN point_redemptions r ON r.participation_id=x.id
            WHERE p.shop_id=#{shopId} AND p.id=#{productId}
              AND (x.status IN ('RESERVED','PAID','QUALIFIED','REFUND_PENDING')
                OR g.status='FORMING' OR (g.status='FAILED' AND g.paid_members>0)
                OR b.status IN ('ACTIVE','QUALIFIED')
                OR r.status IN ('CREATED','FULFILLING','RETURNING'))
            ORDER BY x.id LIMIT 1 FOR UPDATE
            """)
    Long unfinishedProductParticipation(@Param("shopId") Long shopId, @Param("productId") Long productId);

    @Select("""
            SELECT g.id FROM marketing_groups g JOIN marketing_activity_skus s ON s.id=g.activity_sku_id
            JOIN skus k ON k.id=s.sku_id JOIN products p ON p.id=k.product_id
            WHERE p.shop_id=#{shopId} AND p.id=#{productId}
              AND (g.status='FORMING' OR (g.status='FAILED' AND g.paid_members>0))
            ORDER BY g.id LIMIT 1 FOR UPDATE
            """)
    Long unfinishedProductGroup(@Param("shopId") Long shopId, @Param("productId") Long productId);

    @Select("""
            SELECT pool.id FROM marketing_stock_pools pool
            JOIN marketing_activity_skus s ON s.id=pool.activity_sku_id
            JOIN skus k ON k.id=s.sku_id JOIN products p ON p.id=k.product_id
            WHERE p.shop_id=#{shopId} AND p.id=#{productId}
              AND (pool.available_qty>0 OR pool.held_qty>0)
            ORDER BY pool.id LIMIT 1 FOR UPDATE
            """)
    Long productStockPoolBalance(@Param("shopId") Long shopId, @Param("productId") Long productId);

    @Select("""
            SELECT r.id FROM marketing_refund_allocations r
            JOIN after_sale_items ai ON ai.id=r.after_sale_item_id
            JOIN order_items oi ON oi.id=ai.order_item_id
            JOIN skus k ON k.id=oi.sku_id JOIN products p ON p.id=k.product_id
            WHERE p.shop_id=#{shopId} AND p.id=#{productId} AND r.status='PENDING'
            ORDER BY r.id LIMIT 1 FOR UPDATE
            """)
    Long pendingProductRefund(@Param("shopId") Long shopId, @Param("productId") Long productId);

    @Select("SELECT member_level_code FROM marketing_coupon_rules WHERE activity_id=#{id} AND grant_mode='MEMBERSHIP'")
    String monthlyLevel(Long id);

    @Select("""
            SELECT a.id FROM marketing_activities a JOIN marketing_coupon_rules c ON c.activity_id=a.id
            WHERE a.id<>#{id} AND c.grant_mode='MEMBERSHIP' AND c.member_level_code=#{level}
              AND a.status IN ('APPROVED','RUNNING','PAUSED') AND a.start_at<#{end} AND c.claim_end_at>#{start}
            ORDER BY a.id LIMIT 1 FOR UPDATE
            """)
    Long overlappingMonthly(@Param("id") Long id, @Param("level") String level, @Param("start") LocalDateTime start,
                            @Param("end") LocalDateTime end);

    @Select("SELECT DISTINCT sku_id FROM marketing_activity_skus WHERE activity_id=#{id} ORDER BY sku_id")
    List<Long> skuIds(Long id);

    @Select("""
            SELECT a.id FROM marketing_activities a JOIN marketing_activity_skus s ON s.activity_id=a.id
            JOIN marketing_member_prices p ON p.activity_sku_id=s.id AND p.level_code='SILVER'
            WHERE s.sku_id=#{skuId} AND a.status IN ('APPROVED','RUNNING','PAUSED') AND a.end_at>#{now}
              AND p.price>#{newPrice} ORDER BY a.id FOR UPDATE
            """)
    List<Long> priceConflicts(@Param("skuId") Long skuId, @Param("newPrice") BigDecimal newPrice,
                              @Param("now") LocalDateTime now);

    @Select("SELECT * FROM marketing_activities WHERE activity_code=#{code} AND version_no=1 FOR UPDATE")
    MarketingActivity lockFirstVersion(String code);

    @Select("SELECT request_scope FROM marketing_activities WHERE activity_code=#{code} AND version_no=1")
    String initialScope(String code);

    @Select("SELECT version_no FROM marketing_activities WHERE activity_code=#{code} ORDER BY version_no DESC LIMIT 1 FOR UPDATE")
    int lastVersion(String code);

    @Select("SELECT * FROM marketing_activities WHERE creator_user_id=#{actor} AND request_scope=#{scope} AND request_key=#{key} FOR UPDATE")
    MarketingActivity request(@Param("actor") Long actor, @Param("scope") String scope, @Param("key") String key);

    @Select("SELECT id FROM marketing_activities WHERE creator_user_id=#{actor} AND request_scope=#{scope} AND request_key=#{key}")
    Long requestId(@Param("actor") Long actor, @Param("scope") String scope, @Param("key") String key);

    @Select("""
            SELECT actor_user_id,action,summary FROM audit_events WHERE target_type='MARKETING_ACTIVITY' AND target_id=#{id}
            AND action=#{action} ORDER BY id DESC LIMIT 1 FOR UPDATE
            """)
    Audit lastAudit(@Param("id") Long id, @Param("action") String action);

    record Audit(Long actorUserId, String action, String summary) {
    }

    @Select("""
            SELECT id FROM marketing_activities WHERE (status='APPROVED' AND start_at<=#{now})
            OR (status IN ('RUNNING','PAUSED') AND end_at<=#{now}) ORDER BY id LIMIT 100
            """)
    List<Long> due(LocalDateTime now);

    @Select("SELECT * FROM marketing_activities WHERE id=#{id}")
    MarketingActivity find(Long id);

    @Select("SELECT * FROM marketing_activities WHERE id=#{id} FOR SHARE")
    MarketingActivity share(Long id);

    @Select("SELECT * FROM marketing_activities WHERE id=#{id} FOR UPDATE")
    MarketingActivity lock(Long id);

    @Select("""
            <script>SELECT a.* FROM marketing_activities a WHERE 1=1
            <if test='shopId!=null'>AND EXISTS(SELECT 1 FROM marketing_budgets b WHERE b.activity_id=a.id AND b.shop_id=#{shopId})</if>
            <if test='afterId!=null'>AND a.id &lt; #{afterId}</if>
            <if test='type!=null'>AND a.activity_type=#{type}</if>
            <if test='status!=null'>AND a.status=#{status}</if>
            <if test='code!=null'>AND a.activity_code=#{code}</if>
            ORDER BY a.id DESC LIMIT #{limit}</script>
            """)
    List<MarketingActivity> list(@Param("shopId") Long shopId, @Param("afterId") Long afterId,
                                 @Param("type") String type, @Param("status") String status, @Param("code") String code,
                                 @Param("limit") int limit);

    @Insert("""
            INSERT INTO marketing_activities(activity_code,version_no,name,activity_type,status,start_at,end_at,
              eligible_level_codes,creator_user_id,rules_text,supersedes_id,request_scope,request_key,request_hash,created_at,updated_at)
            VALUES(#{activityCode},#{versionNo},#{name},#{activityType},'DRAFT',#{startAt},#{endAt},#{eligibleLevelCodes},
              #{creatorUserId},#{rulesText},#{supersedesId},#{requestScope},#{requestKey},#{requestHash},#{createdAt},#{updatedAt})
            ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(MarketingActivity row);

    @Update("""
            UPDATE marketing_activities SET name=#{name},start_at=#{startAt},end_at=#{endAt},
              eligible_level_codes=#{eligibleLevelCodes},rules_text=#{rulesText},updated_at=#{updatedAt} WHERE id=#{id}
            """)
    void updateDraft(MarketingActivity row);

    @Update("UPDATE marketing_activities SET status=#{status},updated_at=#{now} WHERE id=#{id}")
    void status(@Param("id") Long id, @Param("status") String status, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE marketing_activities SET status=#{status},reviewer_user_id=#{actor},review_reason=#{reason},
              reviewed_at=#{now},updated_at=#{now} WHERE id=#{id}
            """)
    void decision(@Param("id") Long id, @Param("status") String status, @Param("actor") Long actor,
                  @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Select("""
            SELECT s.*,sp.price silver,gp.price gold,pp.price platinum
            FROM marketing_activity_skus s
            LEFT JOIN marketing_member_prices sp ON sp.activity_sku_id=s.id AND sp.level_code='SILVER'
            LEFT JOIN marketing_member_prices gp ON gp.activity_sku_id=s.id AND gp.level_code='GOLD'
            LEFT JOIN marketing_member_prices pp ON pp.activity_sku_id=s.id AND pp.level_code='PLATINUM'
            WHERE s.activity_id=#{id} ORDER BY s.id FOR UPDATE
            """)
    List<ActivityScope> scopes(Long id);

    @Insert("""
            INSERT INTO marketing_activity_skus(activity_id,sku_id,scope_role,activity_price,per_user_limit,sales_limit,
              team_size,participation_seconds,bargain_start_price,bargain_target_price,required_helpers,daily_help_limit,
              max_open_sessions,point_cost_units,unit_cost_amount)
            VALUES(#{activityId},#{draft.skuId},#{draft.scopeRole},#{draft.activityPrice},#{draft.perUserLimit},#{draft.salesLimit},
              #{draft.teamSize},#{draft.participationSeconds},#{draft.bargainStartPrice},#{draft.bargainTargetPrice},
              #{draft.requiredHelpers},#{draft.dailyHelpLimit},#{draft.maxOpenSessions},#{draft.pointCostUnits},#{draft.unitCostAmount})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertScope(ActivityScope.Insert row);

    @Insert("""
            INSERT INTO marketing_member_prices(activity_sku_id,level_code,price)
            VALUES(#{id},'SILVER',#{prices.silver}),(#{id},'GOLD',#{prices.gold}),(#{id},'PLATINUM',#{prices.platinum})
            """)
    void prices(@Param("id") Long id, @Param("prices") MemberPrices prices);

    @Select("""
            SELECT t.threshold_amount,t.reduction_amount,s.sku_id gift_sku_id,t.gift_quantity FROM marketing_threshold_tiers t
            LEFT JOIN marketing_activity_skus s ON s.id=t.gift_activity_sku_id WHERE t.activity_id=#{id} ORDER BY t.id FOR UPDATE
            """)
    List<TierDraft> tiers(Long id);

    @Insert("""
            INSERT INTO marketing_threshold_tiers(activity_id,threshold_amount,reduction_amount,gift_activity_sku_id,gift_quantity)
            VALUES(#{id},#{tier.thresholdAmount},#{tier.reductionAmount},#{giftId},#{tier.giftQuantity})
            """)
    void insertTier(@Param("id") Long id, @Param("tier") TierDraft tier, @Param("giftId") Long giftId);

    @Select("""
            SELECT face_amount,threshold_amount,issue_limit,per_user_limit,claim_end_at,validity_days,fixed_expire_at,
              grant_mode,member_level_code FROM marketing_coupon_rules WHERE activity_id=#{id} FOR UPDATE
            """)
    ActivityCouponRule coupon(Long id);

    @Insert("""
            INSERT INTO marketing_coupon_rules(activity_id,face_amount,threshold_amount,issue_limit,per_user_limit,
              claim_end_at,validity_days,fixed_expire_at,grant_mode,member_level_code)
            VALUES(#{id},#{rule.faceAmount},#{rule.thresholdAmount},#{rule.issueLimit},#{rule.perUserLimit},
              #{claimEnd},#{rule.validityDays},#{fixedEnd},#{rule.grantMode},#{rule.memberLevelCode})
            """)
    void insertCoupon(@Param("id") Long id, @Param("rule") CouponRuleDraft rule,
                      @Param("claimEnd") LocalDateTime claimEnd, @Param("fixedEnd") LocalDateTime fixedEnd);

    @Delete("DELETE FROM marketing_threshold_tiers WHERE activity_id=#{id}")
    void deleteTiers(Long id);

    @Delete("DELETE p FROM marketing_member_prices p JOIN marketing_activity_skus s ON s.id=p.activity_sku_id WHERE s.activity_id=#{id}")
    void deletePrices(Long id);

    @Delete("DELETE FROM marketing_activity_skus WHERE activity_id=#{id}")
    void deleteScopes(Long id);

    @Delete("DELETE FROM marketing_coupon_rules WHERE activity_id=#{id}")
    void deleteCoupon(Long id);

    @Select("SELECT " + MarketingBudgetMapper.BUDGET_COLUMNS +
            " FROM marketing_budgets WHERE activity_id=#{id} ORDER BY id FOR UPDATE")
    List<MarketingBudget> lockBudgets(Long id);

    @Select("SELECT id FROM marketing_budget_entries WHERE budget_id=#{id} ORDER BY id LIMIT 1 FOR UPDATE")
    Long firstEntry(Long id);

    @Insert("""
            INSERT INTO marketing_budgets(activity_id,party,shop_id,budget_amount,platform_share_bp)
            VALUES(#{id},#{proposal.party},#{proposal.shopId},#{proposal.budgetAmount},#{proposal.platformShareBp})
            """)
    void insertBudget(@Param("id") Long id, @Param("proposal") BudgetProposal proposal);

    @Update("""
            UPDATE marketing_budgets SET budget_amount=#{proposal.budgetAmount},platform_share_bp=#{proposal.platformShareBp},
              agreed_by_user_id=NULL,agreed_at=NULL,version=version+1 WHERE id=#{id}
            """)
    void editBudget(@Param("id") Long id, @Param("proposal") BudgetProposal proposal);

    @Delete("DELETE FROM marketing_budgets WHERE id=#{id}")
    void deleteBudget(Long id);

    @Update("UPDATE marketing_budgets SET agreed_by_user_id=#{actor},agreed_at=#{now},version=version+1 WHERE id=#{id}")
    void agree(@Param("id") Long id, @Param("actor") Long actor, @Param("now") LocalDateTime now);

    @Select("SELECT shop_id,agreed_by_user_id FROM marketing_budgets WHERE activity_id=#{id} AND party='MERCHANT' ORDER BY id FOR UPDATE")
    List<AgreementOwner> agreements(Long id);

    record AgreementOwner(Long shopId, Long agreedByUserId) {
    }

    @Select("""
            <script>SELECT a.id FROM marketing_activities a JOIN marketing_activity_skus s ON s.activity_id=a.id
            WHERE a.id&lt;&gt;#{id} AND a.activity_type=#{type} AND a.status IN ('APPROVED','RUNNING','PAUSED')
            AND a.start_at&lt;#{end} AND a.end_at&gt;#{start} AND s.sku_id IN
            <foreach collection='skuIds' item='sku' open='(' separator=',' close=')'>#{sku}</foreach>
            ORDER BY a.id LIMIT 1 FOR UPDATE</script>
            """)
    Long overlapping(@Param("id") Long id, @Param("type") String type, @Param("start") LocalDateTime start,
                     @Param("end") LocalDateTime end, @Param("skuIds") List<Long> skuIds);

    @Insert("""
            INSERT INTO audit_events(actor_user_id,action,target_type,target_id,summary,occurred_at)
            VALUES(#{actor},#{action},'MARKETING_ACTIVITY',#{id},#{reason},#{now})
            """)
    void audit(@Param("actor") Long actor, @Param("id") Long id, @Param("action") String action,
               @Param("reason") String reason, @Param("now") LocalDateTime now);
}
