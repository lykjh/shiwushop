package com.shiwushop.aftersale.mapper.timeout;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.aftersale.entity.shipment.ReturnShipment;
import com.shiwushop.aftersale.entity.timeout.AfterSaleTimeoutTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface AfterSaleTimeoutMapper extends BaseMapper<AfterSaleTimeoutTask> {
    @Select("SELECT * FROM after_sale_timeout_tasks WHERE opened_at IS NOT NULL AND id < #{beforeId} ORDER BY id DESC LIMIT #{limit}")
    List<AfterSaleTimeoutTask> page(@Param("beforeId") long beforeId, @Param("limit") int limit);

    @Select("SELECT id FROM after_sale_timeout_tasks WHERE status='SCHEDULED' AND due_at <= #{now} ORDER BY due_at,id LIMIT 100")
    List<Long> dueIds(@Param("now") LocalDateTime now);

    @Select("SELECT * FROM after_sale_timeout_tasks WHERE id=#{id} FOR UPDATE")
    AfterSaleTimeoutTask selectForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM return_shipments WHERE case_id=#{caseId} FOR UPDATE")
    ReturnShipment shipmentForUpdate(@Param("caseId") Long caseId);

    @Select("SELECT id FROM refund_attempts WHERE case_id=#{caseId} ORDER BY id DESC LIMIT 1 FOR UPDATE")
    Long latestRefundForUpdate(@Param("caseId") Long caseId);

    @Insert("""
            INSERT INTO after_sale_timeout_tasks(case_id,stage,basis_at,due_at,rule_version,status,created_at)
            VALUES (#{caseId},#{stage},#{basis},#{due},#{version},'SCHEDULED',#{now})
            ON DUPLICATE KEY UPDATE id=after_sale_timeout_tasks.id
            """)
    int schedule(@Param("caseId") Long caseId, @Param("stage") String stage,
                 @Param("basis") LocalDateTime basis, @Param("due") LocalDateTime due,
                 @Param("version") int version, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE after_sale_timeout_tasks SET status='STALE'
            WHERE case_id=#{caseId} AND status IN ('SCHEDULED','OPEN','CLAIMED')
              AND (stage<>#{stage} OR basis_at<>#{basis})
            """)
    int staleOther(@Param("caseId") Long caseId, @Param("stage") String stage, @Param("basis") LocalDateTime basis);

    @Update("UPDATE after_sale_timeout_tasks SET status='STALE' WHERE case_id=#{caseId} AND status IN ('SCHEDULED','OPEN','CLAIMED')")
    int staleAll(@Param("caseId") Long caseId);

    @Update("UPDATE after_sale_timeout_tasks SET status='STALE' WHERE id=#{id} AND status='SCHEDULED'")
    int staleScheduled(@Param("id") Long id);

    @Update("UPDATE after_sale_timeout_tasks SET status='OPEN', opened_at=#{now} WHERE id=#{id} AND status='SCHEDULED' AND due_at <= #{now}")
    int activate(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("UPDATE after_sale_timeout_tasks SET status='CLAIMED', assignee_id=#{adminId}, claimed_at=#{now} WHERE id=#{id} AND status='OPEN'")
    int claim(@Param("id") Long id, @Param("adminId") Long adminId, @Param("now") LocalDateTime now);

    @Update("UPDATE after_sale_timeout_tasks SET status='CLOSED', evidence=#{evidence}, conclusion=#{conclusion}, closed_at=#{now} WHERE id=#{id} AND status='CLAIMED' AND assignee_id=#{adminId}")
    int close(@Param("id") Long id, @Param("adminId") Long adminId, @Param("evidence") String evidence,
              @Param("conclusion") String conclusion, @Param("now") LocalDateTime now);
}
