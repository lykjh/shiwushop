package com.shiwushop.trade.settlement.mapper;

import com.shiwushop.trade.settlement.vo.SettlementView;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ManualSettlementMapper {
    private final JdbcTemplate jdbc;

    public record Head(long id, long shopId, long payeeUserId, long createdByUserId, String requestKey,
                       byte[] requestHash, String status, BigDecimal amount, BigDecimal paidAmount,
                       LocalDateTime paidAt, String paymentMethod, String externalReference,
                       String proofReference, String approvalKey, String processingKey,
                       String paymentKey, byte[] paymentHash, String voidKey, String voidReason,
                       String noPaymentEvidence, LocalDateTime createdAt) {
    }

    public record Line(long id, long settlementId, long orderId, Long ownerUserIdAtOrder,
                       String ownershipEvidence, BigDecimal cashPaid, BigDecimal cashRefunded,
                       BigDecimal platformSubsidy, BigDecimal platformReversal,
                       BigDecimal costPlatform, BigDecimal costReversal, BigDecimal payableAmount,
                       byte[] sourceHash) {
    }

    public record Adjustment(long id, long settlementId, Long lineId, Long sourceCaseId,
                             String requestKey, byte[] requestHash, String kind, BigDecimal amountDelta,
                             Long proposedPayeeUserId, String status, String reason,
                             String evidenceReference, LocalDateTime createdAt,
                             String reviewKey, LocalDateTime reviewedAt,
                             String reviewEvidenceReference, String replacementMethod,
                             String replacementExternalReference, String replacementProofReference) {
    }

    public record PaymentFact(long id, BigDecimal amount) {
    }

    public record ItemFacts(int itemCount, int giftItems, int redeemItems,
                            int missingPayableItems, BigDecimal payableTotal) {
    }

    /** 人工结算主单的查询列；顺序必须与 HEAD 的列索引保持一致。 */
    private static final String HEAD_COLUMNS = "id,shop_id,payee_user_id,created_by_user_id,request_key,"
            + "request_hash,status,amount,paid_amount,paid_at,payment_method,external_reference,proof_reference,"
            + "approval_key,processing_key,payment_key,payment_hash,void_key,void_reason,"
            + "no_payment_evidence,created_at";
    /** 按 HEAD_COLUMNS 的列顺序映射人工结算主单。 */
    private static final RowMapper<Head> HEAD = (r, ignored) -> new Head(r.getLong(1), r.getLong(2), r.getLong(3),
            r.getLong(4), r.getString(5), r.getBytes(6), r.getString(7), r.getBigDecimal(8),
            r.getBigDecimal(9), r.getObject(10, LocalDateTime.class), r.getString(11), r.getString(12),
            r.getString(13), r.getString(14), r.getString(15), r.getString(16), r.getBytes(17),
            r.getString(18), r.getString(19), r.getString(20), r.getObject(21, LocalDateTime.class));

    /** 人工结算明细的查询列；顺序必须与 LINE 的列索引保持一致。 */
    private static final String LINE_COLUMNS = "id,settlement_id,order_id,owner_user_id_at_order,ownership_evidence,"
            + "cash_paid,cash_refunded,platform_subsidy,platform_reversal,cost_platform,cost_reversal,"
            + "payable_amount,source_hash";
    /** 按 LINE_COLUMNS 的列顺序映射人工结算明细。 */
    private static final RowMapper<Line> LINE = (r, ignored) -> new Line(r.getLong(1), r.getLong(2), r.getLong(3),
            (Long) r.getObject(4), r.getString(5), r.getBigDecimal(6), r.getBigDecimal(7),
            r.getBigDecimal(8), r.getBigDecimal(9), r.getBigDecimal(10), r.getBigDecimal(11),
            r.getBigDecimal(12), r.getBytes(13));

    public Head byRequest(long adminId, String key) {
        return jdbc.query("SELECT " + HEAD_COLUMNS + " FROM manual_settlements WHERE created_by_user_id=? "
                + "AND request_key=?", HEAD, adminId, key).stream().findFirst().orElse(null);
    }

    public Head head(long id, boolean lock) {
        return jdbc.query("SELECT " + HEAD_COLUMNS + " FROM manual_settlements WHERE id=?"
                + (lock ? " FOR UPDATE" : ""), HEAD, id).stream().findFirst().orElse(null);
    }

    public long reserveRequest(long shopId, long payeeId, long adminId, String key, byte[] hash) {
        jdbc.update("""
                INSERT INTO manual_settlements(shop_id,payee_user_id,created_by_user_id,request_key,request_hash,status)
                VALUES (?,?,?,?,?,'DRAFT') ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)
                """, shopId, payeeId, adminId, key, hash);
        return byRequest(adminId, key).id();
    }

    public List<Long> candidateIds(long shopId, Long afterId, int size) {
        return jdbc.queryForList("""
                SELECT o.id FROM orders o WHERE o.shop_id=? AND (? IS NULL OR o.id<?)
                  AND NOT EXISTS (SELECT 1 FROM manual_settlement_lines l WHERE l.active_order_id=o.id)
                ORDER BY o.id DESC LIMIT ?
                """, Long.class, shopId, afterId, afterId, size);
    }

    public boolean hasActiveLine(long orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM manual_settlement_lines WHERE active_order_id=?)",
                Boolean.class, orderId));
    }

    public List<Long> settlementIds(Long shopId, Long payeeId, Long afterId, int size) {
        return jdbc.queryForList("SELECT id FROM manual_settlements WHERE (? IS NULL OR shop_id=?) "
                + "AND (? IS NULL OR payee_user_id=?) AND (? IS NULL OR id<?) ORDER BY id DESC LIMIT ?",
                Long.class, shopId, shopId, payeeId, payeeId, afterId, afterId, size);
    }

    public PaymentFact successfulPayment(long orderId) {
        return jdbc.query("SELECT id,amount FROM payment_attempts WHERE order_id=? AND status='SUCCESS' "
                + "ORDER BY id DESC LIMIT 1", (r, ignored) -> new PaymentFact(r.getLong(1), r.getBigDecimal(2)),
                orderId).stream().findFirst().orElse(null);
    }

    public ItemFacts itemFacts(long orderId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*),COALESCE(SUM(item_kind='GIFT'),0),COALESCE(SUM(item_kind='REDEEM'),0),
                       COALESCE(SUM((item_kind='SALE' AND payable_amount IS NULL)
                         OR (item_kind='REDEEM' AND (payable_amount IS NULL OR payable_amount<>0))
                         OR (item_kind='GIFT' AND payable_amount IS NOT NULL)
                         OR item_kind NOT IN ('SALE','REDEEM','GIFT')),0),
                       COALESCE(SUM(CASE WHEN item_kind='SALE' THEN payable_amount ELSE 0 END),0)
                FROM order_items WHERE order_id=?
                """, (r, ignored) -> new ItemFacts(r.getInt(1), r.getInt(2), r.getInt(3),
                r.getInt(4), r.getBigDecimal(5)), orderId);
    }

    public void amount(long id, BigDecimal amount) {
        jdbc.update("UPDATE manual_settlements SET amount=? WHERE id=? AND status='DRAFT'", amount, id);
    }

    public void addLine(long settlementId, long orderId, Long ownerAtOrder, String evidence,
                        BigDecimal cashPaid, BigDecimal cashRefunded, BigDecimal subsidy,
                        BigDecimal reversal, BigDecimal cost, BigDecimal costReversal,
                        BigDecimal payable, byte[] hash, String includedRefunds) {
        jdbc.update("""
                INSERT INTO manual_settlement_lines(settlement_id,order_id,owner_user_id_at_order,
                  ownership_evidence,cash_paid,cash_refunded,platform_subsidy,platform_reversal,
                  cost_platform,cost_reversal,payable_amount,source_hash,included_refund_case_ids)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, settlementId, orderId, ownerAtOrder, evidence, cashPaid, cashRefunded,
                subsidy, reversal, cost, costReversal, payable, hash, includedRefunds);
    }

    public List<Line> lines(long settlementId) {
        return jdbc.query("SELECT " + LINE_COLUMNS + " FROM manual_settlement_lines WHERE settlement_id=? "
                + "ORDER BY order_id", LINE, settlementId);
    }

    public boolean includedRefund(long lineId, long caseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT JSON_CONTAINS(included_refund_case_ids,"
                + "CAST(? AS JSON)) FROM manual_settlement_lines WHERE id=?", Boolean.class,
                Long.toString(caseId), lineId));
    }

    public int approve(long id, long adminId, String key, LocalDateTime now) {
        return jdbc.update("UPDATE manual_settlements SET status='APPROVED',approved_by_user_id=?,"
                + "approved_at=?,approval_key=? WHERE id=? AND status='DRAFT'", adminId, now, key, id);
    }

    public int processing(long id, long adminId, String key, LocalDateTime now) {
        return jdbc.update("UPDATE manual_settlements SET status='PROCESSING',processing_by_user_id=?,"
                + "processing_at=?,processing_key=? WHERE id=? AND status='APPROVED'", adminId, now, key, id);
    }

    public int paid(long id, long adminId, String key, byte[] hash, BigDecimal amount,
                    LocalDateTime paidAt, String method, String external, String proof) {
        return jdbc.update("""
                UPDATE manual_settlements SET status='PAID',paid_by_user_id=?,payment_key=?,payment_hash=?,
                  paid_amount=?,paid_at=?,payment_method=?,external_reference=?,proof_reference=?
                WHERE id=? AND status='PROCESSING'
                """, adminId, key, hash, amount, paidAt, method, external, proof, id);
    }

    public int voidSettlement(long id, long adminId, String key, String reason,
                              String noPaymentEvidence, LocalDateTime now) {
        return jdbc.update("""
                UPDATE manual_settlements SET status='VOID',voided_by_user_id=?,voided_at=?,void_key=?,
                  void_reason=?,no_payment_evidence=?
                WHERE id=? AND status IN ('DRAFT','APPROVED','PROCESSING')
                """, adminId, now, key, reason, noPaymentEvidence, id);
    }

    public void releaseLines(long id) {
        jdbc.update("UPDATE manual_settlement_lines SET active=0 WHERE settlement_id=? AND active=1", id);
    }

    public Adjustment adjustmentByKey(long id, String key) {
        return jdbc.query("SELECT id,settlement_id,line_id,source_case_id,request_key,request_hash,kind,"
                + "amount_delta,proposed_payee_user_id,status,reason,evidence_reference,created_at,review_key,"
                + "reviewed_at,review_evidence_reference,replacement_method,replacement_external_reference,"
                + "replacement_proof_reference FROM manual_settlement_adjustments WHERE settlement_id=? AND request_key=?",
                ADJUSTMENT, id, key).stream().findFirst().orElse(null);
    }

    public Adjustment adjustment(long id) {
        return jdbc.query("SELECT id,settlement_id,line_id,source_case_id,request_key,request_hash,kind,"
                + "amount_delta,proposed_payee_user_id,status,reason,evidence_reference,created_at,review_key,"
                + "reviewed_at,review_evidence_reference,replacement_method,replacement_external_reference,"
                + "replacement_proof_reference FROM manual_settlement_adjustments WHERE id=?", ADJUSTMENT, id)
                .stream().findFirst().orElse(null);
    }

    /** 按调整流水查询的列顺序映射人工结算调整记录。 */
    private static final RowMapper<Adjustment> ADJUSTMENT = (r, ignored) -> new Adjustment(r.getLong(1),
            r.getLong(2), (Long) r.getObject(3), (Long) r.getObject(4), r.getString(5), r.getBytes(6),
            r.getString(7), r.getBigDecimal(8), (Long) r.getObject(9), r.getString(10),
            r.getString(11), r.getString(12), r.getObject(13, LocalDateTime.class),
            r.getString(14), r.getObject(15, LocalDateTime.class), r.getString(16),
            r.getString(17), r.getString(18), r.getString(19));

    public List<Adjustment> adjustments(long settlementId) {
        return jdbc.query("SELECT id,settlement_id,line_id,source_case_id,request_key,request_hash,kind,"
                + "amount_delta,proposed_payee_user_id,status,reason,evidence_reference,created_at,review_key,"
                + "reviewed_at,review_evidence_reference,replacement_method,replacement_external_reference,"
                + "replacement_proof_reference FROM manual_settlement_adjustments WHERE settlement_id=? ORDER BY id",
                ADJUSTMENT, settlementId);
    }

    public int addAdjustment(long settlementId, Long lineId, Long caseId, String key, byte[] hash,
                             String kind, BigDecimal delta, Long payee, String method, String external,
                             String proof, String reason, String evidence, long adminId) {
        return jdbc.update("""
                INSERT INTO manual_settlement_adjustments(settlement_id,line_id,source_case_id,request_key,
                  request_hash,kind,amount_delta,proposed_payee_user_id,replacement_method,
                  replacement_external_reference,replacement_proof_reference,reason,evidence_reference,
                  created_by_user_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE id=id
                """, settlementId, lineId, caseId, key, hash, kind, delta, payee,
                method, external, proof, reason, evidence, adminId);
    }

    public int reviewAdjustment(long id, long adminId, String key, String evidence, LocalDateTime now) {
        return jdbc.update("UPDATE manual_settlement_adjustments SET status='REVIEWED',reviewed_by_user_id=?,"
                + "reviewed_at=?,review_key=?,review_evidence_reference=? WHERE id=? AND status='OPEN'",
                adminId, now, key, evidence, id);
    }

    public void audit(long actor, String action, long id, String summary, LocalDateTime now) {
        jdbc.update("INSERT INTO audit_events(actor_user_id,action,target_type,target_id,summary,occurred_at) "
                + "VALUES (?,?,'MANUAL_SETTLEMENT',?,?,?)", actor, action, id, summary, now);
    }

    public List<SettlementView.Audit> audit(long id) {
        return jdbc.query("SELECT id,actor_user_id,action,summary,occurred_at FROM audit_events "
                + "WHERE target_type='MANUAL_SETTLEMENT' AND target_id=? ORDER BY id",
                (r, ignored) -> new SettlementView.Audit(r.getLong(1), (Long) r.getObject(2),
                        r.getString(3), r.getString(4), r.getObject(5, LocalDateTime.class)), id);
    }
}
