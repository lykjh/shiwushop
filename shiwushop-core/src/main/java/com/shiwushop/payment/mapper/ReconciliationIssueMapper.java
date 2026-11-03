package com.shiwushop.payment.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.payment.entity.ReconciliationIssue;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
public interface ReconciliationIssueMapper extends BaseMapper<ReconciliationIssue> {
 @Select("SELECT * FROM payment_reconciliation_issues WHERE id=#{id} FOR UPDATE")
 ReconciliationIssue selectForUpdate(@Param("id") Long id);
 @Select("<script>SELECT * FROM payment_reconciliation_issues WHERE 1=1 <if test='status != null'>AND status=#{status}</if> <if test='afterId != null'>AND id &lt; #{afterId}</if> ORDER BY id DESC LIMIT #{limit}</script>")
 List<ReconciliationIssue> selectSlice(@Param("status") String status, @Param("afterId") Long afterId, @Param("limit") int limit);
 @Update("UPDATE payment_reconciliation_issues SET claimed_by=#{actor} WHERE id=#{id} AND status='OPEN' AND claimed_by IS NULL")
 int claim(@Param("id") Long id, @Param("actor") Long actor);
 @Update("UPDATE payment_reconciliation_issues SET status='RESOLVED', resolved_at=#{now}, resolved_evidence_id=#{evidenceId}, conclusion_type=#{type}, conclusion=#{conclusion}, manual_handling_reference=#{manual} WHERE id=#{id} AND status='OPEN' AND claimed_by=#{actor}")
 int resolve(@Param("id") Long id, @Param("actor") Long actor, @Param("now") LocalDateTime now,
 @Param("evidenceId") Long evidenceId, @Param("type") String type, @Param("conclusion") String conclusion, @Param("manual") String manual);
}
