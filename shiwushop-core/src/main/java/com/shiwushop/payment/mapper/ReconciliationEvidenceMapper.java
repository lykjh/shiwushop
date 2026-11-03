package com.shiwushop.payment.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.payment.entity.ReconciliationEvidence;
import org.apache.ibatis.annotations.*;
import java.util.List;
public interface ReconciliationEvidenceMapper extends BaseMapper<ReconciliationEvidence> {
 @Select("SELECT * FROM payment_reconciliation_evidence WHERE issue_id=#{issueId} ORDER BY id")
 List<ReconciliationEvidence> selectForIssue(@Param("issueId") Long issueId);
}
