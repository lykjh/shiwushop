package com.shiwushop.trade.settlement.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class SettlementView {
    private SettlementView() {
    }

    public record Candidate(
        /** 关联订单 ID。 */
        long orderId,
        /** 所属店铺 ID。 */
        long shopId,
        /** 下单时的店主用户 ID 快照。 */
        Long ownerUserIdAtOrder,
        /** 满足处理条件的最早时间。 */
        LocalDateTime eligibleAt,
        /** 结算资格：INELIGIBLE 不满足、REVIEW_REQUIRED 待核对、NO_PAYMENT 无须付款、PAYABLE 可结算。 */
        String state,
        /** 该订单当前可结算给收款人的金额，单位元。 */
        BigDecimal payableAmount,
        /** 尚未具备的结算事实或阻塞原因。 */
        List<String> missingFacts
    ) {
    }

    public record Candidates(
        /** 当前对象的明细列表。 */
        List<Candidate> items,
        /** 下一页的起始 ID 游标。 */
        Long nextAfterId
    ) {
    }

    public record Summary(
        /** 当前记录 ID。 */
        long id,
        /** 所属店铺 ID。 */
        long shopId,
        /** 收款用户 ID。 */
        long payeeUserId,
        /** 结算单状态：DRAFT 草稿、APPROVED 已审、PROCESSING 付款中、PAID 已付、VOID 作废。 */
        String status,
        /** 结算单核定应付金额，单位元。 */
        BigDecimal amount,
        /** 已支付金额，单位元。 */
        BigDecimal paidAmount,
        /** 记录创建时间。 */
        LocalDateTime createdAt
    ) {
    }

    public record Page(
        /** 当前对象的明细列表。 */
        List<Summary> items,
        /** 下一页的起始 ID 游标。 */
        Long nextAfterId
    ) {
    }

    public record Line(
        /** 当前记录 ID。 */
        long id,
        /** 关联订单 ID。 */
        long orderId,
        /** 下单时的店主用户 ID 快照。 */
        Long ownerUserIdAtOrder,
        /** 本次结算收款归属的核对凭据。 */
        String ownershipEvidence,
        /** 订单现金实付金额，单位元。 */
        BigDecimal cashPaid,
        /** 已确认成功的现金退款金额，单位元。 */
        BigDecimal cashRefunded,
        /** 平台现金优惠承担金额，单位元。 */
        BigDecimal platformSubsidy,
        /** 已退回的平台现金优惠承担金额，单位元。 */
        BigDecimal platformReversal,
        /** 赠品或积分兑换成本中平台承担的金额，单位元。 */
        BigDecimal costPlatform,
        /** 已退回的平台成本承担金额，单位元。 */
        BigDecimal costReversal,
        /** 现金实付减退款并加平台净承担后的结算金额，单位元。 */
        BigDecimal payableAmount
    ) {
    }

    public record Adjustment(
        /** 当前记录 ID。 */
        long id,
        /** 结算单明细 ID。 */
        Long lineId,
        /** 触发调整的原售后单 ID。 */
        Long sourceCaseId,
        /** 调整类别：REFUND 退款、AMOUNT 金额、PAYEE 收款人、EVIDENCE 凭据。 */
        String kind,
        /** 更正申请的金额增减值，单位元。 */
        BigDecimal amountDelta,
        /** 更正申请中拟改为的收款用户 ID。 */
        Long proposedPayeeUserId,
        /** 调整单状态：OPEN 待复核、REVIEWED 已复核。 */
        String status,
        /** 本次业务操作的原因。 */
        String reason,
        /** 操作凭据引用。 */
        String evidenceReference,
        /** 记录创建时间。 */
        LocalDateTime createdAt,
        /** 审核完成时间。 */
        LocalDateTime reviewedAt,
        /** 人工核对结果凭据。 */
        String reviewEvidenceReference,
        /** 替换的付款方式，仅凭据更正时使用。 */
        String replacementMethod,
        /** 替换的外部流水，仅凭据更正时使用。 */
        String replacementExternalReference,
        /** 替换的付款凭据，仅凭据更正时使用。 */
        String replacementProofReference
    ) {
    }

    public record Audit(
        /** 当前记录 ID。 */
        long id,
        /** 本次操作人用户 ID。 */
        Long actorUserId,
        /** 审计操作代码。 */
        String action,
        /** 审计或差异摘要。 */
        String summary,
        /** 审计事件发生时间。 */
        LocalDateTime occurredAt
    ) {
    }

    public record Detail(
        /** 当前记录 ID。 */
        long id,
        /** 所属店铺 ID。 */
        long shopId,
        /** 收款用户 ID。 */
        long payeeUserId,
        /** 结算单状态：DRAFT 草稿、APPROVED 已审、PROCESSING 付款中、PAID 已付、VOID 作废。 */
        String status,
        /** 结算单核定应付金额，单位元。 */
        BigDecimal amount,
        /** 已支付金额，单位元。 */
        BigDecimal paidAmount,
        /** 支付或结算完成时间。 */
        LocalDateTime paidAt,
        /** 实际付款方式。 */
        String paymentMethod,
        /** 外部付款流水或业务编号。 */
        String externalReference,
        /** 实际付款凭据引用。 */
        String proofReference,
        /** 记录创建时间。 */
        LocalDateTime createdAt,
        /** 结算单明细列表。 */
        List<Line> lines,
        /** 结算单后续调整记录。 */
        List<Adjustment> adjustments,
        /** 审计事件列表。 */
        List<Audit> audit
    ) {
    }
}
