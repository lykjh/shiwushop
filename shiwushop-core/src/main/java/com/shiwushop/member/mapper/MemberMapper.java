package com.shiwushop.member.mapper;

import com.shiwushop.member.model.*;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface MemberMapper {
    @Select("""
            SELECT task.id,item.order_id,task.order_item_id,task.reward_kind,task.status,task.eligible_at,
                   task.credited_growth_amount,task.credited_point_units,task.exclusion_reason,
                   task.user_id,task.rule_version,task.next_attempt_at,task.last_error_code
            FROM member_reward_tasks task JOIN order_items item ON item.id=task.order_item_id
            WHERE (#{userId} IS NULL OR task.user_id=#{userId}) AND (#{afterId} IS NULL OR task.id<#{afterId})
              AND (#{kind} IS NULL OR task.reward_kind=#{kind}) AND (#{status} IS NULL OR task.status=#{status})
              AND (#{orderId} IS NULL OR item.order_id=#{orderId})
            ORDER BY task.id DESC LIMIT #{limit}
            """)
    List<AdminRewardTaskView> adminRewardTasks(@Param("userId") Long userId, @Param("afterId") Long afterId,
                                               @Param("kind") String kind, @Param("status") String status,
                                               @Param("orderId") Long orderId, @Param("limit") int limit);

    @Select("SELECT id,status,eligible_at,next_attempt_at,claim_until FROM member_reward_tasks WHERE id=#{id} FOR UPDATE")
    MemberTaskControl lockTask(Long id);

    @Update("""
            UPDATE member_reward_tasks SET status=#{state},next_attempt_at=#{next},claim_token=NULL,claim_until=NULL
            WHERE id=#{id}
            """)
    void retryTask(@Param("id") Long id, @Param("state") String state, @Param("next") LocalDateTime next);

    @Insert("""
            INSERT INTO audit_events(actor_user_id,action,target_type,target_id,summary,occurred_at)
            VALUES(#{actor},CONCAT('MEMBER_REWARD_RETRY_FROM_',#{oldState}),'MEMBER_REWARD_TASK',#{taskId},#{reason},#{now})
            """)
    void auditTaskRetry(@Param("actor") Long actor, @Param("taskId") Long taskId, @Param("oldState") String oldState,
                        @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM member_point_lots WHERE user_id=#{userId} AND (#{afterId} IS NULL OR id<#{afterId})
              AND (#{status} IS NULL OR
                CASE WHEN expires_at<=#{now} AND available_units>0 AND held_units=0 THEN 'EXPIRED' ELSE status END=#{status})
            ORDER BY id DESC LIMIT #{limit}
            """)
    List<MemberPointLot> pointLots(@Param("userId") Long userId, @Param("afterId") Long afterId,
                                   @Param("status") String status, @Param("now") LocalDateTime now,
                                   @Param("limit") int limit);

    @Select("""
            SELECT task.id,item.order_id,task.order_item_id,task.reward_kind,task.status,task.eligible_at,
                   task.credited_growth_amount,task.credited_point_units,task.exclusion_reason
            FROM member_reward_tasks task JOIN order_items item ON item.id=task.order_item_id
            WHERE task.user_id=#{userId} AND (#{afterId} IS NULL OR task.id<#{afterId})
              AND (#{kind} IS NULL OR task.reward_kind=#{kind}) AND (#{status} IS NULL OR task.status=#{status})
              AND (#{orderId} IS NULL OR item.order_id=#{orderId})
            ORDER BY task.id DESC LIMIT #{limit}
            """)
    List<MemberRewardTaskView> rewardTaskViews(@Param("userId") Long userId, @Param("afterId") Long afterId,
                                               @Param("kind") String kind, @Param("status") String status,
                                               @Param("orderId") Long orderId, @Param("limit") int limit);

    @Select("""
            SELECT level_code AS code,level_name AS name,min_growth_amount,monthly_coupon_count,
                   coupon_face_amount AS monthly_coupon_face_amount,coupon_threshold_amount AS monthly_coupon_threshold_amount
            FROM membership_levels WHERE enabled=1 ORDER BY rank_no
            """)
    List<com.shiwushop.member.vo.MemberLevelVO> publicLevels();

    @Select("""
            SELECT level_code AS code,level_name AS name,min_growth_amount,monthly_coupon_count,
              coupon_face_amount AS monthly_coupon_face_amount,coupon_threshold_amount AS monthly_coupon_threshold_amount
            FROM membership_levels WHERE level_code=#{code} FOR UPDATE
            """)
    com.shiwushop.member.vo.MemberLevelVO lockLevel(String code);

    @Select("SELECT * FROM member_accounts WHERE user_id=#{userId}")
    MemberAccount account(Long userId);

    @Select("SELECT EXISTS(SELECT 1 FROM users WHERE id=#{userId})")
    boolean userExists(Long userId);

    @Select("SELECT COALESCE(SUM(available_units),0) FROM member_point_lots WHERE user_id=#{userId} AND expires_at<=#{now}")
    long expiredAvailableUnits(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Select("SELECT u.id FROM users u LEFT JOIN member_accounts a ON a.user_id=u.id WHERE a.user_id IS NULL ORDER BY u.id LIMIT 50")
    List<Long> uninitializedUsers();

    @Select("SELECT DISTINCT user_id FROM member_point_lots WHERE status='ACTIVE' AND expires_at<=#{now} AND available_units>0 ORDER BY user_id LIMIT 50")
    List<Long> usersWithExpiredPoints(LocalDateTime now);

    @Select("""
            SELECT COALESCE(SUM(COALESCE(item.payable_amount,item.line_amount)-COALESCE((SELECT SUM(ai.refund_amount)
              FROM after_sale_items ai JOIN after_sale_cases ac ON ac.id=ai.case_id
              WHERE ai.order_item_id=item.id AND ac.status='REFUNDED'),0)),0)
            FROM member_reward_tasks task JOIN order_items item ON item.id=task.order_item_id
            JOIN orders o ON o.id=item.order_id WHERE task.user_id=#{userId} AND task.reward_kind='GROWTH'
              AND task.status IN ('WAIT_RECEIPT','WAIT_AFTERSALE','READY') AND o.status='PAID'
            """)
    BigDecimal pendingGrowth(Long userId);

    @Select("""
            SELECT * FROM member_growth_entries WHERE user_id=#{userId} AND (#{beforeId} IS NULL OR id<#{beforeId})
              AND (#{orderId} IS NULL OR order_item_id IN (SELECT id FROM order_items WHERE order_id=#{orderId}))
            ORDER BY id DESC LIMIT #{limit}
            """)
    List<MemberGrowthEntry> growthEntries(@Param("userId") Long userId, @Param("beforeId") Long beforeId,
                                          @Param("limit") int limit, @Param("orderId") Long orderId);

    @Select("""
            SELECT * FROM member_point_entries WHERE user_id=#{userId} AND (#{beforeId} IS NULL OR id<#{beforeId})
              AND (#{orderId} IS NULL OR order_id=#{orderId}) ORDER BY id DESC LIMIT #{limit}
            """)
    List<MemberPointEntry> pointEntries(@Param("userId") Long userId, @Param("beforeId") Long beforeId,
                                        @Param("limit") int limit, @Param("orderId") Long orderId);

    @Select("SELECT to_level_code FROM member_level_changes WHERE user_id=#{userId} AND effective_at<=#{at} ORDER BY effective_at DESC,id DESC LIMIT 1")
    String levelAt(@Param("userId") Long userId, @Param("at") LocalDateTime at);

    @Select("SELECT * FROM member_point_lots WHERE user_id=#{userId} AND available_units>0 AND expires_at<=#{now} ORDER BY expires_at,id FOR UPDATE")
    List<MemberPointLot> expiredLots(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT task.id AS task_id,item.order_id FROM member_reward_tasks task
            JOIN order_items item ON item.id=task.order_item_id
            JOIN orders o ON o.id=item.order_id
            WHERE task.status IN ('WAIT_PAYMENT','WAIT_RECEIPT','READY','WAIT_AFTERSALE')
              AND task.next_attempt_at<=#{now} AND (task.claim_until IS NULL OR task.claim_until<=#{now})
              AND o.status='PAID'
              AND (task.reward_kind='POINTS' OR (o.fully_received_at IS NOT NULL AND o.fully_received_at<=#{receiptBefore}))
            ORDER BY task.next_attempt_at,task.id LIMIT 50
            """)
    List<com.shiwushop.member.dto.RewardTaskCandidate> pending(@Param("now") LocalDateTime now,
                                                               @Param("receiptBefore") LocalDateTime receiptBefore);

    @Update("""
            UPDATE member_reward_tasks SET claim_token=#{token},claim_until=#{until}
            WHERE id=#{id} AND status IN ('WAIT_PAYMENT','WAIT_RECEIPT','READY','WAIT_AFTERSALE')
              AND next_attempt_at<=#{now} AND (claim_until IS NULL OR claim_until<=#{now})
            """)
    int claim(@Param("id") Long id, @Param("token") String token, @Param("now") LocalDateTime now,
              @Param("until") LocalDateTime until);

    @Select("SELECT COUNT(*) FROM member_reward_tasks WHERE id=#{id} AND claim_token=#{token} AND claim_until>#{now}")
    int ownsClaim(@Param("id") Long id, @Param("token") String token, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE member_reward_tasks SET claim_token=NULL,claim_until=NULL,next_attempt_at=#{retry},last_error_code=#{error}
            WHERE id=#{id} AND claim_token=#{token}
            """)
    void release(@Param("id") Long id, @Param("token") String token, @Param("retry") LocalDateTime retry,
                 @Param("error") String error);

    @Select("SELECT level_code,min_growth_amount FROM membership_levels WHERE enabled=1 ORDER BY min_growth_amount")
    List<MembershipLevel> levels();

    @Insert("""
            INSERT INTO member_accounts(user_id,level_code,joined_at,reward_start_at)
            SELECT id,'ORDINARY',GREATEST(created_at,#{start}),#{start} FROM users WHERE id=#{userId}
            ON DUPLICATE KEY UPDATE user_id=member_accounts.user_id
            """)
    void initialize(@Param("userId") Long userId, @Param("start") LocalDateTime start);

    @Select("SELECT * FROM member_accounts WHERE user_id=#{userId} FOR UPDATE")
    MemberAccount lockAccount(Long userId);

    @Update("""
            UPDATE member_accounts SET level_code=#{levelCode},growth_amount=#{growthAmount},
            available_point_units=#{availablePointUnits},held_point_units=#{heldPointUnits},
            point_debt_units=#{pointDebtUnits},version=version+1 WHERE user_id=#{userId}
            """)
    void updateAccount(MemberAccount account);

    @Insert("""
            INSERT INTO member_level_changes(user_id,to_level_code,effective_at,operation_key)
            SELECT user_id,'ORDINARY',joined_at,CONCAT('MEMBER_INIT:',user_id)
            FROM member_accounts WHERE user_id=#{userId}
            ON DUPLICATE KEY UPDATE id=member_level_changes.id
            """)
    void initializeHistory(Long userId);

    @Insert("""
            INSERT INTO member_reward_tasks(user_id,order_item_id,reward_kind,status,exclusion_reason,next_attempt_at,rule_version)
            VALUES(#{userId},#{itemId},#{kind},#{status},#{reason},#{now},1)
            """)
    void insertTask(@Param("userId") Long userId, @Param("itemId") Long itemId, @Param("kind") String kind,
                    @Param("status") String status, @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Select("""
            SELECT id,user_id,order_item_id,reward_kind,status,credited_growth_amount,credited_point_units,eligible_at
            FROM member_reward_tasks WHERE order_item_id=#{itemId} AND reward_kind=#{kind} FOR UPDATE
            """)
    MemberRewardTask task(@Param("itemId") Long itemId, @Param("kind") String kind);

    @Update("""
            UPDATE member_reward_tasks SET status='APPLIED',eligible_at=#{at},credited_growth_amount=#{growth},
            credited_point_units=#{points},last_error_code=NULL,claim_token=NULL,claim_until=NULL
            WHERE id=#{id}
            """)
    void applied(@Param("id") Long id, @Param("at") LocalDateTime at,
                 @Param("growth") BigDecimal growth, @Param("points") long points);

    @Update("""
            UPDATE member_reward_tasks SET status='EXCLUDED',exclusion_reason='FULL_REFUND',claim_token=NULL,claim_until=NULL
            WHERE id=#{id}
            """)
    void excludeRefund(Long id);

    @Update("""
            UPDATE member_reward_tasks task JOIN order_items item ON item.id=task.order_item_id
            JOIN orders o ON o.id=item.order_id
            SET task.eligible_at=DATE_ADD(o.fully_received_at,INTERVAL 7 DAY),
                task.next_attempt_at=DATE_ADD(o.fully_received_at,INTERVAL 7 DAY),task.status='READY'
            WHERE item.order_id=#{orderId} AND task.reward_kind='GROWTH' AND task.status='WAIT_RECEIPT'
              AND o.fully_received_at IS NOT NULL
            """)
    void receipt(Long orderId);

    @Update("""
            UPDATE member_reward_tasks SET status='WAIT_AFTERSALE',next_attempt_at=#{retryAt},claim_token=NULL,claim_until=NULL
            WHERE id=#{id}
            """)
    void waitAfterSale(@Param("id") Long id, @Param("retryAt") LocalDateTime retryAt);

    @Select("SELECT * FROM member_point_lots WHERE source_key=#{key} FOR UPDATE")
    MemberPointLot lot(String key);

    @Insert("""
            INSERT INTO member_point_lots(user_id,source_key,order_item_id,issued_units,available_units,debt_offset_units,
            earned_at,expires_at,status) VALUES(#{userId},#{sourceKey},#{orderItemId},#{issuedUnits},#{availableUnits},
            #{debtOffsetUnits},#{earnedAt},#{expiresAt},#{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertLot(MemberPointLot lot);

    @Update("""
            UPDATE member_point_lots SET available_units=#{availableUnits},held_units=#{heldUnits},spent_units=#{spentUnits},
            expired_units=#{expiredUnits},reversed_units=#{reversedUnits},debt_offset_units=#{debtOffsetUnits},status=#{status}
            WHERE id=#{id}
            """)
    void updateLot(MemberPointLot lot);

    @Insert("""
            INSERT INTO member_point_entries(user_id,event_kind,operation_key,order_id,after_sale_case_id,
            available_delta,held_delta,debt_delta,available_after,held_after,debt_after,actor_user_id,reason,evidence_reference)
            VALUES(#{userId},#{eventKind},#{operationKey},#{orderId},#{afterSaleCaseId},#{availableDelta},#{heldDelta},
            #{debtDelta},#{availableAfter},#{heldAfter},#{debtAfter},#{actorUserId},#{reason},#{evidenceReference})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertPointEntry(MemberPointEntry entry);

    @Select("SELECT id FROM member_point_entries WHERE operation_key=#{key} FOR UPDATE")
    Long pointEntryId(String key);

    @Select("SELECT * FROM member_point_entries WHERE operation_key=#{key} FOR UPDATE")
    MemberPointEntry pointEntry(String key);

    @Select("SELECT * FROM member_point_lots WHERE user_id=#{userId} AND available_units>0 AND expires_at>#{now} ORDER BY expires_at,id FOR UPDATE")
    List<MemberPointLot> availableLots(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO member_point_allocations(entry_id,lot_id,allocation_kind,units)
            VALUES(#{entryId},#{lotId},#{kind},#{units})
            """)
    void allocate(@Param("entryId") Long entryId, @Param("lotId") Long lotId,
                  @Param("kind") String kind, @Param("units") long units);

    // 写路径持有账户锁后读取最新账本，不能沿用鉴权建立的旧一致性快照。
    @Select("SELECT * FROM member_growth_entries WHERE operation_key=#{key} FOR UPDATE")
    MemberGrowthEntry growthEntry(String key);

    @Select("SELECT * FROM member_growth_entries WHERE id=#{id} FOR UPDATE")
    MemberGrowthEntry growthEntryById(Long id);

    @Insert("""
            INSERT INTO audit_events(actor_user_id,action,target_type,target_id,summary,occurred_at)
            VALUES(#{actor},#{action},'MEMBER',#{userId},#{summary},#{now})
            """)
    void auditAdjustment(@Param("actor") Long actor, @Param("userId") Long userId, @Param("action") String action,
                         @Param("summary") String summary, @Param("now") LocalDateTime now);

    @Select("SELECT COALESCE(-SUM(delta_amount),0) FROM member_growth_entries WHERE source_entry_id=#{sourceId} AND delta_amount<0 FOR UPDATE")
    BigDecimal reversedGrowth(Long sourceId);

    @Insert("""
            INSERT INTO member_growth_entries(user_id,order_item_id,after_sale_case_id,source_entry_id,event_kind,
            operation_key,delta_amount,balance_after,old_level_code,new_level_code,actor_user_id,reason,evidence_reference)
            VALUES(#{userId},#{orderItemId},#{afterSaleCaseId},#{sourceEntryId},#{eventKind},#{operationKey},
            #{deltaAmount},#{balanceAfter},#{oldLevelCode},#{newLevelCode},#{actorUserId},#{reason},#{evidenceReference})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertGrowthEntry(MemberGrowthEntry entry);

    @Insert("""
            INSERT INTO member_level_changes(user_id,from_level_code,to_level_code,effective_at,operation_key,growth_entry_id)
            VALUES(#{entry.userId},#{entry.oldLevelCode},#{entry.newLevelCode},#{at},#{entry.operationKey},#{entry.id})
            """)
    void levelChanged(@Param("entry") MemberGrowthEntry entry, @Param("at") LocalDateTime at);
}
