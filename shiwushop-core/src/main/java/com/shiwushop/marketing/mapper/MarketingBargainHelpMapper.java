package com.shiwushop.marketing.mapper;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface MarketingBargainHelpMapper {
    record Bargain(Long id, Long participationId, Long activitySkuId, Long userId,
                   BigDecimal startAmount, BigDecimal targetAmount,
                   BigDecimal currentAmount, int requiredHelpers, int helpCount, String status,
                   LocalDateTime helpExpiresAt, LocalDateTime purchaseExpiresAt) {
    }

    record Scope(Long activityId, String activityCode, int dailyHelpLimit) {
    }

    record Help(Long bargainId, int stepNo, BigDecimal decreaseAmount, BigDecimal amountAfter,
                String requestKey, LocalDateTime helpedAt, BigDecimal targetAmount) {
    }

    @Select("""
            SELECT id,participation_id,activity_sku_id,user_id,start_amount,target_amount,current_amount,required_helpers,
              help_count,status,help_expires_at,purchase_expires_at
            FROM marketing_bargains WHERE id=#{id} FOR UPDATE
            """)
    Bargain lock(long id);

    @Select("SELECT status FROM marketing_participations WHERE id=#{id} FOR UPDATE")
    String lockParticipation(long id);

    @Update("UPDATE marketing_participations SET status='QUALIFIED',expires_at=#{until} " +
            "WHERE id=#{id} AND status='RESERVED'")
    int qualifyParticipation(@Param("id") long id, @Param("until") LocalDateTime until);

    @Select("""
            SELECT s.activity_id,a.activity_code,s.daily_help_limit FROM marketing_activity_skus s
            JOIN marketing_activities a ON a.id=s.activity_id WHERE s.id=#{scope}
            """)
    Scope scope(long scope);

    @Select("SELECT h.bargain_id,h.step_no,h.decrease_amount,h.amount_after,h.request_key,h.helped_at," +
            "b.target_amount FROM marketing_bargain_helps h JOIN marketing_bargains b ON b.id=h.bargain_id " +
            "WHERE h.bargain_id=#{bargain} AND h.helper_user_id=#{helper}")
    Help help(@Param("bargain") long bargain, @Param("helper") long helper);

    @Select("SELECT h.bargain_id,h.step_no,h.decrease_amount,h.amount_after,h.request_key,h.helped_at," +
            "b.target_amount FROM marketing_bargain_helps h JOIN marketing_bargains b ON b.id=h.bargain_id " +
            "WHERE h.helper_user_id=#{helper} AND h.request_key=#{key}")
    Help helpByKey(@Param("helper") long helper, @Param("key") String key);

    @Insert("""
            INSERT INTO marketing_user_quotas(activity_id,activity_code,user_id,quota_kind,period_key)
            VALUES(#{activity},#{code},#{user},'HELP',#{day})
            ON DUPLICATE KEY UPDATE id=id
            """)
    void ensureHelpQuota(@Param("activity") long activity, @Param("code") String code,
                         @Param("user") long user, @Param("day") String day);

    @Update("""
            UPDATE marketing_user_quotas SET consumed_count=consumed_count+1,version=version+1
            WHERE activity_code=#{code} AND user_id=#{user} AND quota_kind='HELP' AND period_key=#{day}
              AND consumed_count<#{cap}
            """)
    int useHelpQuota(@Param("code") String code, @Param("user") long user,
                     @Param("day") String day, @Param("cap") int cap);

    @Update("""
            UPDATE marketing_bargains SET current_amount=#{after},help_count=help_count+1,
              status=#{status},purchase_expires_at=#{purchaseUntil},version=version+1
            WHERE id=#{id} AND status='ACTIVE' AND help_count=#{before}
            """)
    int advance(@Param("id") long id, @Param("before") int before, @Param("after") BigDecimal after,
                @Param("status") String status, @Param("purchaseUntil") LocalDateTime purchaseUntil);

    @Insert("""
            INSERT INTO marketing_bargain_helps(bargain_id,helper_user_id,request_key,step_no,amount_before,
              decrease_amount,amount_after,helped_at)
            VALUES(#{bargain},#{helper},#{key},#{step},#{before},#{decrease},#{after},#{now})
            """)
    void record(@Param("bargain") long bargain, @Param("helper") long helper, @Param("key") String key,
                @Param("step") int step, @Param("before") BigDecimal before,
                @Param("decrease") BigDecimal decrease, @Param("after") BigDecimal after,
                @Param("now") LocalDateTime now);
}
