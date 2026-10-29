package com.shiwushop.commerce.mapper.payment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.commerce.entity.payment.RefundAttempt;
import com.shiwushop.commerce.entity.status.RefundAttemptStatus;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.math.BigDecimal;

public interface RefundAttemptMapper extends BaseMapper<RefundAttempt> {
    @Select("SELECT * FROM refund_attempts WHERE case_id=#{caseId} ORDER BY id DESC LIMIT 1")
    RefundAttempt selectLatest(@Param("caseId") Long caseId);
    BigDecimal successfulAmount(@Param("orderId") Long orderId);
    List<Long> unresolvedIds(@Param("before") LocalDateTime before, @Param("limit") int limit);
    Long oldestUnresolvedSeconds(@Param("before") LocalDateTime before, @Param("now") LocalDateTime now);

    // Claim housekeeping must not reset updated_at, which controls the next reconciliation scan.
    @Update("""
            UPDATE refund_attempts SET reconcile_claim_token=#{token}, updated_at=updated_at,
                reconcile_claim_until=DATE_ADD(NOW(3), INTERVAL #{leaseSeconds} SECOND)
            WHERE id=#{id} AND status IN ('PENDING','UNKNOWN')
              AND requested_at <= #{before} AND updated_at <= #{before}
              AND (reconcile_claim_until IS NULL OR reconcile_claim_until <= NOW(3))
            """)
    int claimReconciliation(@Param("id") Long id, @Param("token") String token,
                            @Param("before") LocalDateTime before,
                            @Param("leaseSeconds") long leaseSeconds);

    @Select("""
            SELECT COUNT(*) FROM refund_attempts WHERE id=#{id} AND reconcile_claim_token=#{token}
              AND reconcile_claim_until > NOW(3)
            """)
    int ownsReconciliation(@Param("id") Long id, @Param("token") String token);

    @Update("""
            UPDATE refund_attempts SET reconcile_claim_token=NULL, reconcile_claim_until=NULL,
                updated_at=updated_at
            WHERE id=#{id} AND reconcile_claim_token=#{token}
            """)
    int releaseReconciliation(@Param("id") Long id, @Param("token") String token);

    @Select("SELECT * FROM refund_attempts WHERE id=#{id} FOR UPDATE")
    RefundAttempt selectForUpdate(@Param("id") Long id);

    @Update("""
            UPDATE refund_attempts SET updated_at=#{now} WHERE id=#{id}
              AND status IN ('PENDING','UNKNOWN') AND reconcile_claim_token=#{token}
              AND reconcile_claim_until > NOW(3)
            """)
    int markCheckedClaimed(@Param("id") Long id, @Param("token") String token,
                           @Param("now") LocalDateTime now);

    @Update("UPDATE refund_attempts SET updated_at=#{now} WHERE id=#{id} AND status IN ('PENDING','UNKNOWN')")
    int markChecked(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE refund_attempts SET status=#{next}, resolved_at=#{resolvedAt},
                provider_refund_no=#{providerRefundNo}
            WHERE id=#{id} AND status IN ('PENDING','UNKNOWN')
            """)
    int resolve(@Param("id") Long id, @Param("next") RefundAttemptStatus next,
                @Param("resolvedAt") LocalDateTime resolvedAt,
                @Param("providerRefundNo") String providerRefundNo);

    @Update("""
            UPDATE refund_attempts SET status='UNKNOWN' WHERE id=#{id} AND status='PENDING'
            """)
    int markUnknown(@Param("id") Long id);
}
