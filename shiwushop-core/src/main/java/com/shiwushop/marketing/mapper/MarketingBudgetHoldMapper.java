package com.shiwushop.marketing.mapper;

import com.shiwushop.marketing.model.MarketingBudgetHold;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface MarketingBudgetHoldMapper {
    @Insert("""
            INSERT INTO marketing_budget_holds(budget_id,operation_key,source_kind,source_id,parent_hold_id,
              authorized_amount,held_amount,expires_at,status)
            VALUES(#{budgetId},#{operationKey},#{sourceKind},#{sourceId},#{parentHoldId},#{authorizedAmount},#{heldAmount},#{expiresAt},'HELD')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(MarketingBudgetHold hold);

    @Update("UPDATE marketing_budgets SET held_amount=held_amount+#{delta},version=version+1 WHERE id=#{id}")
    void held(@Param("id") Long id, @Param("delta") BigDecimal delta);

    @Insert("""
            INSERT INTO marketing_budget_entries(budget_id,hold_id,operation_key,event_kind,held_delta,spent_delta,held_after,
              spent_after,budget_after,created_at)
            SELECT id,#{hold},#{key},#{kind},#{heldDelta},0,held_amount,spent_amount,budget_amount,#{now}
            FROM marketing_budgets WHERE id=#{budget}
            """)
    void entry(@Param("budget") Long budget, @Param("hold") Long hold, @Param("key") String key,
               @Param("kind") String kind, @Param("heldDelta") BigDecimal heldDelta, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM marketing_budget_holds WHERE source_kind='COUPON' AND source_id=#{coupon} ORDER BY budget_id,id FOR UPDATE")
    List<MarketingBudgetHold> couponHolds(Long coupon);

    @Update("UPDATE marketing_budget_holds SET released_amount=released_amount+held_amount,held_amount=0,status='RELEASED' WHERE id=#{id}")
    void release(Long id);
}
