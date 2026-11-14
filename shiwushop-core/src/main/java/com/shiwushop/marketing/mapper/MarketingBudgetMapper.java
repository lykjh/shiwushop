package com.shiwushop.marketing.mapper;

import com.shiwushop.marketing.model.MarketingBudget;
import com.shiwushop.marketing.model.MarketingBudgetEntry;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface MarketingBudgetMapper {
    /** 营销预算查询共用字段，包含额度、占用、支出及承担协议版本。 */
    String BUDGET_COLUMNS = "id,activity_id,party,shop_id,budget_amount,held_amount,spent_amount,platform_share_bp,agreed_at,version";

    @Select("SELECT " + BUDGET_COLUMNS + " FROM marketing_budgets WHERE id=#{id}")
    MarketingBudget find(Long id);

    @Select("SELECT " + BUDGET_COLUMNS + " FROM marketing_budgets WHERE id=#{id} FOR UPDATE")
    MarketingBudget lock(Long id);

    @Select("SELECT EXISTS(SELECT 1 FROM marketing_activities WHERE id=#{id})")
    boolean activityExists(Long id);

    @Select("<script>SELECT " + BUDGET_COLUMNS + " FROM marketing_budgets WHERE activity_id=#{activityId} "
            + "<if test='shopId!=null'>AND shop_id=#{shopId}</if> ORDER BY id</script>")
    List<MarketingBudget> list(@Param("activityId") Long activityId, @Param("shopId") Long shopId);

    @Select("SELECT * FROM marketing_budget_entries WHERE budget_id=#{budgetId} AND operation_key=#{key} FOR UPDATE")
    MarketingBudgetEntry operation(@Param("budgetId") Long budgetId, @Param("key") String key);

    @Select("""
            <script>SELECT * FROM marketing_budget_entries WHERE budget_id=#{budgetId}
            <if test='afterId!=null'>AND id &lt; #{afterId}</if> ORDER BY id DESC LIMIT #{limit}</script>
            """)
    List<MarketingBudgetEntry> entries(@Param("budgetId") Long budgetId, @Param("afterId") Long afterId,
                                       @Param("limit") int limit);

    @Update("UPDATE marketing_budgets SET budget_amount=#{amount},version=version+1 WHERE id=#{id}")
    void updateAmount(@Param("id") Long id, @Param("amount") BigDecimal amount);

    @Insert("""
            INSERT INTO marketing_budget_entries(budget_id,operation_key,event_kind,held_delta,spent_delta,held_after,
              spent_after,actor_user_id,reason,budget_delta_amount,budget_after,evidence_reference,created_at)
            VALUES(#{budgetId},#{operationKey},'ADJUST',0,0,#{heldAfter},#{spentAfter},#{actorUserId},#{reason},
              #{budgetDeltaAmount},#{budgetAfter},#{evidenceReference},#{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertAdjustment(MarketingBudgetEntry entry);

    @Insert("""
            INSERT INTO audit_events(actor_user_id,action,target_type,target_id,summary,occurred_at)
            VALUES(#{actor},'MARKETING_BUDGET_ADJUSTED','MARKETING_BUDGET',#{budgetId},#{summary},#{now})
            """)
    void audit(@Param("actor") Long actor, @Param("budgetId") Long budgetId, @Param("summary") String summary,
               @Param("now") LocalDateTime now);
}
