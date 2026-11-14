package com.shiwushop.aftersale.mapper;

import com.shiwushop.aftersale.vo.AfterSaleTimelineVO;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface AfterSaleAuditMapper {
    @Insert("""
            INSERT INTO audit_events (actor_user_id,action,target_type,target_id,summary,occurred_at)
            VALUES (#{actor},#{action},'AFTER_SALE',#{caseId},#{summary},#{now})
            """)
    int record(@Param("actor") Long actor, @Param("action") String action,
               @Param("caseId") Long caseId, @Param("summary") String summary,
               @Param("now") LocalDateTime now);

    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class, id = true),
            @Arg(column = "action", javaType = String.class),
            @Arg(column = "summary", javaType = String.class),
            @Arg(column = "occurred_at", javaType = LocalDateTime.class)
    })
    @Select("""
            SELECT id, action, summary, occurred_at FROM audit_events
            WHERE target_type='AFTER_SALE' AND target_id=#{caseId} AND id<#{beforeId}
              AND action IN ('AFTER_SALE_REQUESTED','AFTER_SALE_APPROVED','AFTER_SALE_REJECTED',
                             'AFTER_SALE_WITHDRAWN','RETURN_SHIPPED','RETURN_RECEIVED',
                             'REFUND_REQUESTED','REFUND_SUCCEEDED','ZERO_CASH_CANCELLED','POINTS_CANCELLED')
            ORDER BY id DESC LIMIT #{limit}
            """)
    List<AfterSaleTimelineVO.Event> publicEvents(@Param("caseId") Long caseId,
                                                   @Param("beforeId") Long beforeId,
                                                   @Param("limit") int limit);
}
