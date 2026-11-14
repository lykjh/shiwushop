package com.shiwushop.marketing.mapper;

import com.shiwushop.marketing.service.MarketingPricingService.Allocation;
import com.shiwushop.marketing.model.MarketingBudgetHold;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface MarketingOrderMapper {
    @Insert("""
            INSERT INTO order_promotion_allocations(order_item_id,activity_id,benefit_kind,user_coupon_id,tier_id,
              amount,platform_amount,merchant_amount,platform_share_bp,rule_snapshot)
            VALUES(#{item},#{a.activityId},#{a.kind},#{a.userCouponId},#{a.tierId},#{a.amount},
              #{a.platformAmount},#{a.amount}-#{a.platformAmount},#{a.platformShareBp},
              JSON_SET(#{a.ruleSnapshot},'$.reservationVersion',#{version}))
            """)
    void snapshot(@Param("item") long item, @Param("a") Allocation allocation, @Param("version") Long version);

    @Update("""
            UPDATE user_coupons SET status='RESERVED',reserved_order_id=#{order},reserved_until=#{until},
              reservation_version=reservation_version+1
            WHERE id=#{id} AND user_id=#{user} AND status='AVAILABLE' AND expires_at>#{now}
            """)
    int reserveCoupon(@Param("id") long id, @Param("user") long user, @Param("order") long order,
                      @Param("until") LocalDateTime until, @Param("now") LocalDateTime now);

    @Select("SELECT reservation_version FROM user_coupons WHERE id=#{id} FOR UPDATE")
    long couponVersion(long id);

    @Update("""
            UPDATE marketing_budget_holds SET held_amount=held_amount-#{amount},status='PARTIAL'
            WHERE id=#{id} AND held_amount>=#{amount}
            """)
    int transfer(@Param("id") long id, @Param("amount") BigDecimal amount);

    @Update("""
            UPDATE marketing_budgets SET held_amount=held_amount+#{held},spent_amount=spent_amount+#{spent},version=version+1
            WHERE id=#{id} AND held_amount+spent_amount+#{held}+#{spent}<=budget_amount
            """)
    int changeBudget(@Param("id") long id, @Param("held") BigDecimal held, @Param("spent") BigDecimal spent);

    @Insert("""
            INSERT INTO marketing_budget_entries(budget_id,hold_id,operation_key,event_kind,held_delta,spent_delta,
              held_after,spent_after,budget_after,order_id,after_sale_case_id,created_at)
            SELECT id,#{hold},#{key},#{kind},#{held},#{spent},held_amount,spent_amount,budget_amount,#{order},#{sale},#{now}
            FROM marketing_budgets WHERE id=#{budget}
            """)
    void entry(@Param("budget") long budget, @Param("hold") long hold, @Param("key") String key,
               @Param("kind") String kind,
               @Param("held") BigDecimal held, @Param("spent") BigDecimal spent, @Param("order") long order,
               @Param("sale") Long sale, @Param("now") LocalDateTime now);

    @Select("""
            SELECT h.* FROM marketing_budget_holds h WHERE h.source_kind='ORDER' AND h.source_id=#{order}
            ORDER BY h.budget_id,h.id FOR UPDATE
            """)
    List<MarketingBudgetHold> holds(long order);

    @Update("""
            UPDATE marketing_budget_holds SET held_amount=0,spent_amount=#{spent},released_amount=released_amount+#{released},
              status=#{state} WHERE id=#{id}
            """)
    void finishHold(@Param("id") long id, @Param("spent") BigDecimal spent, @Param("released") BigDecimal released,
                    @Param("state") String state);

    @Update("UPDATE marketing_budget_holds SET held_amount=held_amount+#{amount},status='HELD' WHERE id=#{id}")
    void restoreParent(@Param("id") long id, @Param("amount") BigDecimal amount);

    record CouponUse(long id, long version) {
    }

    @Select("""
            SELECT DISTINCT a.user_coupon_id id,CAST(a.rule_snapshot->>'$.reservationVersion' AS UNSIGNED) version
            FROM order_promotion_allocations a JOIN order_items i ON i.id=a.order_item_id
            WHERE i.order_id=#{order} AND a.user_coupon_id IS NOT NULL
            """)
    CouponUse couponUse(long order);

    @Update("""
            UPDATE user_coupons SET status=#{state},reserved_order_id=NULL,reserved_until=NULL,used_order_id=#{used}
            WHERE id=#{use.id} AND reserved_order_id=#{order} AND reservation_version=#{use.version} AND status='RESERVED'
            """)
    int finishCoupon(@Param("use") CouponUse use, @Param("order") long order, @Param("state") String state,
                     @Param("used") Long used);

    record SaleScope(long id, String code, Integer perUserLimit, int quantity) {
    }

    @Select("""
            SELECT s.id,a.activity_code code,s.per_user_limit,i.quantity FROM order_items i
            JOIN order_promotion_allocations p ON p.order_item_id=i.id AND p.benefit_kind IN ('MEMBER','LIMITED')
            JOIN marketing_activity_skus s ON s.id=CAST(p.rule_snapshot->>'$.activitySkuId' AS UNSIGNED)
            JOIN marketing_activities a ON a.id=s.activity_id WHERE i.order_id=#{order} ORDER BY a.activity_code,s.id FOR UPDATE
            """)
    List<SaleScope> scopes(long order);

    @Update("""
            UPDATE marketing_activity_skus SET reserved_qty=reserved_qty+#{held},paid_qty=paid_qty+#{paid}
            WHERE id=#{id} AND (sales_limit IS NULL OR reserved_qty+paid_qty+#{held}+#{paid}<=sales_limit)
            """)
    int scopeDelta(@Param("id") long id, @Param("held") int held, @Param("paid") int paid);

    @Insert("""
            INSERT INTO marketing_user_quotas(activity_id,activity_code,user_id,quota_kind,period_key)
            SELECT activity_id,#{code},#{user},'BUY','ALL' FROM marketing_activity_skus WHERE id=#{scope}
            ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(marketing_user_quotas.id)
            """)
    void initializeBuy(@Param("scope") long scope, @Param("code") String code, @Param("user") long user);

    @Update("""
            UPDATE marketing_user_quotas SET reserved_count=reserved_count+#{held},consumed_count=consumed_count+#{paid}
            WHERE activity_code=#{code} AND user_id=#{user} AND quota_kind='BUY' AND period_key='ALL'
              AND (#{held}<=0 OR reserved_count+consumed_count+#{held}<=#{cap})
            """)
    int quotaDelta(@Param("code") String code, @Param("user") long user, @Param("held") int held,
                   @Param("paid") int paid, @Param("cap") int cap);

    @Select("""
            SELECT b.id FROM marketing_budgets b WHERE EXISTS
              (SELECT 1 FROM marketing_budget_holds h WHERE h.budget_id=b.id AND h.source_kind='ORDER' AND h.source_id=#{order})
            ORDER BY b.id FOR UPDATE
            """)
    List<Long> lockOrderBudgets(long order);

    record RefundShare(long saleItem, long orderItem, long allocation, long activity, int quantity, String ranges,
                       BigDecimal platform, BigDecimal merchant) {
    }

    @Select("""
            SELECT ai.id saleItem,i.id orderItem,a.id allocation,a.activity_id activity,i.quantity,
              ai.unit_allocation ranges,a.platform_amount platform,a.merchant_amount merchant
            FROM after_sale_items ai JOIN order_items i ON ai.order_item_id=i.id
            JOIN order_promotion_allocations a ON a.order_item_id=i.id WHERE ai.case_id=#{sale} ORDER BY ai.id,a.id
            """)
    List<RefundShare> refundShares(long sale);

    @Insert("""
            INSERT INTO marketing_refund_allocations(after_sale_item_id,benefit_key,operation_key,cash_refund_amount,
              growth_reversal_amount,point_reversal_units,status,applied_at)
            SELECT ai.id,'BASE',CONCAT('REFUND:',ai.id,':BASE'),ai.refund_amount,COALESCE(-g.delta_amount,0),
              COALESCE(-p.available_delta+p.debt_delta,0),'APPLIED',#{now}
            FROM after_sale_items ai LEFT JOIN member_growth_entries g ON g.operation_key=CONCAT('REFUND_GROWTH:',ai.case_id,':',ai.order_item_id)
            LEFT JOIN member_point_entries p ON p.operation_key=CONCAT('REFUND_POINTS:',ai.case_id,':',ai.order_item_id)
            WHERE ai.case_id=#{sale}
            """)
    void refundedBase(@Param("sale") long sale, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO marketing_refund_allocations(after_sale_item_id,promotion_allocation_id,benefit_key,operation_key,
              platform_reversal_amount,merchant_reversal_amount,status,applied_at)
            VALUES(#{a.saleItem},#{a.allocation},CONCAT('PROMO:',#{a.allocation}),CONCAT('REFUND:',#{a.saleItem},':',#{a.allocation}),
              #{platform},#{merchant},'APPLIED',#{now})
            """)
    void refundedShare(@Param("a") RefundShare share, @Param("platform") BigDecimal platform,
                       @Param("merchant") BigDecimal merchant, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE marketing_budget_holds SET spent_amount=spent_amount-#{amount},refunded_amount=refunded_amount+#{amount},
              status=IF(authorized_amount=refunded_amount,'RELEASED','PARTIAL') WHERE id=#{id} AND spent_amount>=#{amount}
            """)
    int refundHold(@Param("id") long id, @Param("amount") BigDecimal amount);

    @Select("""
            SELECT NOT EXISTS(SELECT 1 FROM order_items i WHERE i.order_id=#{order} AND i.item_kind='SALE' AND i.quantity>
              (SELECT COALESCE(SUM(ai.quantity),0) FROM after_sale_items ai JOIN after_sale_cases c ON c.id=ai.case_id
               WHERE ai.order_item_id=i.id AND c.status='REFUNDED'))
            """)
    boolean fullyReturned(long order);

    @Update("""
            UPDATE user_coupons SET status=#{state},used_order_id=NULL,reservation_version=reservation_version+1
            WHERE id=#{use.id} AND status='USED' AND used_order_id=#{order} AND reservation_version=#{use.version}
            """)
    int returnCoupon(@Param("use") CouponUse use, @Param("order") long order, @Param("state") String state);
}
