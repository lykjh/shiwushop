package com.shiwushop.mapper.after_sale;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface AfterSaleAuditMapper {
    @Insert("""
            INSERT INTO audit_events (actor_user_id,action,target_type,target_id,summary,occurred_at)
            VALUES (#{actor},#{action},'AFTER_SALE',#{caseId},#{summary},#{now})
            """)
    int record(@Param("actor") Long actor, @Param("action") String action,
               @Param("caseId") Long caseId, @Param("summary") String summary,
               @Param("now") LocalDateTime now);
}
