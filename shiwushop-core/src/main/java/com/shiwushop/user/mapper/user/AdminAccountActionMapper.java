package com.shiwushop.user.mapper.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AdminAccountActionMapper {
    @Insert("""
            INSERT INTO audit_events(actor_user_id,action,target_type,target_id,summary,occurred_at)
            VALUES(#{actor},#{action},'USER_ACCOUNT',#{target},#{summary},#{now})
            """)
    void audit(@Param("actor") Long actor, @Param("action") String action,
               @Param("target") Long target, @Param("summary") String summary,
               @Param("now") LocalDateTime now);

    @Select("SELECT LAST_INSERT_ID()")
    Long lastAuditId();

    @Insert("""
            INSERT INTO account_action_items(audit_event_id,actor_user_id,target_user_id,reason,created_at)
            VALUES(#{auditId},#{actor},#{target},#{reason},#{now})
            """)
    void actionItem(@Param("auditId") Long auditId, @Param("actor") Long actor,
                    @Param("target") Long target, @Param("reason") String reason,
                    @Param("now") LocalDateTime now);

    @Update("UPDATE account_action_items SET status='RESOLVED' " +
            "WHERE target_user_id=#{target} AND status='OPEN'")
    void resolveOpen(@Param("target") Long target);
}
