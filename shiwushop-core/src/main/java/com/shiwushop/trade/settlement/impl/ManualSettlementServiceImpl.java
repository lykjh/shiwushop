package com.shiwushop.trade.settlement.impl;

import com.shiwushop.trade.settlement.AfterSaleSettlementFacts;
import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.service.MarketingOrderSettlement;
import com.shiwushop.marketing.service.MarketingSettlementFacts;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.entity.status.OrderStatus;
import com.shiwushop.trade.mapper.order.OrderMapper;
import com.shiwushop.trade.settlement.ManualSettlementService;
import com.shiwushop.trade.settlement.dto.SettlementRequests;
import com.shiwushop.trade.settlement.mapper.ManualSettlementMapper;
import com.shiwushop.trade.settlement.vo.SettlementView;
import com.shiwushop.user.service.AccountAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ManualSettlementServiceImpl implements ManualSettlementService {
    /** 人工结算计算使用的零金额，单位为元，保留两位小数。 */
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private final ManualSettlementMapper settlements;
    private final OrderMapper orders;
    private final ShopService shops;
    private final AccountAccess accounts;
    private final MarketingOrderSettlement sourceLocks;
    private final MarketingSettlementFacts marketing;
    private final AfterSaleSettlementFacts afterSales;
    private final Clock businessClock;

    private record Quote(SettlementView.Candidate candidate, BigDecimal cashPaid, BigDecimal cashRefunded,
                         BigDecimal subsidy, BigDecimal reversal, BigDecimal cost, BigDecimal costReversal,
                         byte[] sourceHash, List<Long> includedRefunds) {
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public SettlementView.Candidates candidates(long adminId, long shopId, Long afterId, int size) {
        accounts.requireAdmin(adminId);
        if (shops.findById(shopId) == null)
            throw new BusinessException(404, "店铺不存在");
        int limit = pageSize(size);
        var ids = settlements.candidateIds(shopId, afterId, limit + 1);
        var results = ids.stream().limit(limit).map(id -> quote(orders.selectById(id), null).candidate()).toList();
        return new SettlementView.Candidates(results, ids.size() > limit ? ids.get(limit - 1) : null);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public SettlementView.Page adminList(long adminId, Long shopId, Long afterId, int size) {
        accounts.requireAdmin(adminId);
        return page(shopId, null, afterId, size);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public SettlementView.Page merchantList(long userId, Long afterId, int size) {
        accounts.requireActive(userId);
        return page(null, userId, afterId, size);
    }

    private SettlementView.Page page(Long shopId, Long payeeId, Long afterId, int size) {
        int limit = pageSize(size);
        var ids = settlements.settlementIds(shopId, payeeId, afterId, limit + 1);
        var summaries = ids.stream().limit(limit).map(id -> settlements.head(id, false))
                .map(h -> new SettlementView.Summary(h.id(), h.shopId(), h.payeeUserId(), h.status(),
                        h.amount(), h.paidAmount(), h.createdAt())).toList();
        return new SettlementView.Page(summaries, ids.size() > limit ? ids.get(limit - 1) : null);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SettlementView.Detail adminDetail(long adminId, long id) {
        accounts.requireAdmin(adminId);
        var head = requireHead(id, true);
        if ("PAID".equals(head.status()))
            syncRefunds(head, adminId);
        return view(id);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public SettlementView.Detail merchantDetail(long userId, long id) {
        accounts.requireActive(userId);
        var head = settlements.head(id, false);
        if (head == null || head.payeeUserId() != userId)
            throw new BusinessException(404, "结算单不存在");
        return view(id);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SettlementView.Detail create(long adminId, SettlementRequests.Create request) {
        accounts.requireAdmin(adminId);
        var selections = request.orders().stream().sorted(Comparator.comparing(SettlementRequests.Selection::orderId))
                .toList();
        if (selections.size() != new HashSet<>(selections.stream()
                .map(SettlementRequests.Selection::orderId).toList()).size())
            throw new BusinessException(400, "结算订单重复");
        var parts = new ArrayList<String>();
        parts.add(request.shopId().toString());
        parts.add(request.payeeUserId().toString());
        for (var line : selections) {
            parts.add(line.orderId().toString());
            parts.add(line.ownershipEvidence().trim());
        }
        byte[] requestHash = hash(parts);
        var previous = settlements.byRequest(adminId, request.requestKey());
        if (previous != null)
            return repeatCreate(previous, requestHash);
        accounts.requireShopOwner(request.payeeUserId(), "收款主体不存在");
        if (shops.lockById(request.shopId()) == null)
            throw new BusinessException(404, "店铺不存在");
        long id = settlements.reserveRequest(request.shopId(), request.payeeUserId(), adminId,
                request.requestKey(), requestHash);
        var head = requireHead(id, true);
        if (!Arrays.equals(head.requestHash(), requestHash))
            throw new BusinessException(409, "结算请求键内容冲突");
        if (!settlements.lines(id).isEmpty())
            return view(id);
        BigDecimal amount = ZERO;
        for (var selection : selections) {
            sourceLocks.lockSource(selection.orderId());
            Order order = orders.selectForUpdate(selection.orderId());
            if (order == null || !request.shopId().equals(order.getShopId()))
                throw new BusinessException(404, "订单不属于该店铺");
            if (settlements.hasActiveLine(order.getId()))
                throw new BusinessException(409, "订单 " + order.getId() + " 已纳入结算单，请刷新候选订单");
            Quote quote = quote(order, selection.ownershipEvidence());
            if (!"PAYABLE".equals(quote.candidate().state()))
                throw new BusinessException(409, "订单暂不可结算：" + quote.candidate().missingFacts());
            settlements.addLine(id, order.getId(), order.getShopOwnerUserIdAtOrder(),
                    selection.ownershipEvidence().trim(), quote.cashPaid(), quote.cashRefunded(), quote.subsidy(),
                    quote.reversal(), quote.cost(), quote.costReversal(), quote.candidate().payableAmount(),
                    quote.sourceHash(), jsonIds(quote.includedRefunds()));
            amount = amount.add(quote.candidate().payableAmount());
        }
        settlements.amount(id, amount);
        settlements.audit(adminId, "MANUAL_SETTLEMENT_CREATED", id, "人工结算单已生成，待审核",
                LocalDateTime.now(businessClock));
        return view(id);
    }

    private SettlementView.Detail repeatCreate(ManualSettlementMapper.Head previous, byte[] hash) {
        if (!Arrays.equals(previous.requestHash(), hash))
            throw new BusinessException(409, "结算请求键内容冲突");
        return view(previous.id());
    }

    private Quote quote(Order order, String ownershipEvidence) {
        LocalDateTime due = order.getFullyReceivedAt() == null ? null : order.getFullyReceivedAt().plusDays(7);
        List<String> missing = new ArrayList<>();
        if (order.getStatus() != OrderStatus.PAID)
            missing.add("ORDER_NOT_PAID");
        if (due == null)
            missing.add("FULL_RECEIPT_MISSING");
        else if (LocalDateTime.now(businessClock).isBefore(due))
            missing.add("SEVEN_DAYS_NOT_REACHED");
        if (!missing.isEmpty())
            return notPayable(order, due, "INELIGIBLE", missing);
        if (order.getShopOwnerUserIdAtOrder() == null && (ownershipEvidence == null || ownershipEvidence.isBlank()))
            missing.add("ORDER_OWNER_SNAPSHOT_MISSING");
        var progress = orders.fulfillmentProgress(order.getId());
        if (progress == null || !List.of("RECEIVED", "NO_REMAINING").contains(progress.getReceiptProgress()))
            return notPayable(order, due, "INELIGIBLE", List.of("FULL_RECEIPT_PROGRESS_INCOMPLETE"));
        var itemFacts = settlements.itemFacts(order.getId());
        if (itemFacts.itemCount() == 0)
            missing.add("ORDER_ITEMS_MISSING");
        var cases = afterSales.cases(order.getId());
        if (cases.stream().anyMatch(c -> c.unresolvedAttempts() > 0))
            missing.add("REFUND_RESULT_UNRESOLVED");
        if (cases.stream().anyMatch(c -> List.of("REQUESTED", "WAITING_RETURN", "WAITING_RECEIPT", "REFUNDING")
                .contains(c.status())))
            missing.add("AFTER_SALE_IN_PROGRESS");
        if (missing.contains("REFUND_RESULT_UNRESOLVED") || missing.contains("AFTER_SALE_IN_PROGRESS"))
            return notPayable(order, due, "INELIGIBLE", missing);
        var promotions = marketing.snapshot(order.getId());
        var promotion = promotions.totals();
        var reversals = new HashMap<Long, MarketingSettlementFacts.Reversal>();
        for (var reversal : promotions.reversals())
            reversals.put(reversal.caseId(), reversal);
        BigDecimal cashPaid = ZERO;
        var payment = settlements.successfulPayment(order.getId());
        if ("CASH".equals(order.getSettlementKind())) {
            if (payment == null || payment.amount().compareTo(order.getTotalAmount()) != 0)
                missing.add("SUCCESSFUL_PAYMENT_MISSING_OR_MISMATCH");
            else
                cashPaid = payment.amount();
        } else if (payment != null || order.getTotalAmount().signum() != 0) {
            missing.add("NON_CASH_PAYMENT_MISMATCH");
        }
        if (Integer.valueOf(1).equals(order.getPricingVersion())) {
            if (order.getOriginalAmount() == null
                    || order.getOriginalAmount().subtract(order.getDiscountAmount())
                    .compareTo(order.getTotalAmount()) != 0
                    || promotion.cashDiscount().compareTo(order.getDiscountAmount()) != 0
                    || promotion.cashPlatform().compareTo(order.getPlatformSubsidyAmount()) != 0
                    || promotion.cashPlatform().add(promotion.cashMerchant())
                    .compareTo(promotion.cashDiscount()) != 0
                    || itemFacts.missingPayableItems() != 0
                    || itemFacts.payableTotal().compareTo(order.getTotalAmount()) != 0)
                missing.add("ORIGINAL_ALLOCATION_MISMATCH");
        } else if (!"CASH".equals(order.getSettlementKind())
                || order.getDiscountAmount().signum() != 0
                || order.getPlatformSubsidyAmount().signum() != 0) {
            missing.add("LEGACY_PRICING_SNAPSHOT_MISSING");
        }
        if (itemFacts.giftItems() > promotion.giftCostRows()
                || itemFacts.redeemItems() > promotion.pointCostRows()
                || ("POINTS".equals(order.getSettlementKind()) && promotion.pointCostRows() == 0)
                || promotion.invalidCostRows() > 0)
            missing.add("FROZEN_SUPPLY_COST_MISSING");
        BigDecimal cashRefunded = ZERO;
        BigDecimal reversal = ZERO;
        BigDecimal costReversal = ZERO;
        var included = new ArrayList<Long>();
        var sourceParts = new ArrayList<String>(List.of(order.getId().toString(), order.getStatus().name(),
                order.getSettlementKind(), Objects.toString(order.getFulfillmentState()),
                Objects.toString(progress), order.getTotalAmount().toPlainString(),
                Objects.toString(order.getOriginalAmount()), order.getDiscountAmount().toPlainString(),
                order.getPlatformSubsidyAmount().toPlainString(), Objects.toString(order.getFullyReceivedAt()),
                Objects.toString(order.getShopOwnerUserIdAtOrder()), Objects.toString(order.getPricingVersion()),
                Objects.toString(payment == null ? null : payment.id()),
                Objects.toString(payment == null ? null : payment.amount()),
                promotion.toString(), itemFacts.toString()));
        for (var sale : cases) {
            var share = reversals.get(sale.id());
            sourceParts.add(sale.toString());
            sourceParts.add(Objects.toString(share));
            if (!"REFUNDED".equals(sale.status())) {
                if (sale.successfulCash().signum() != 0)
                    missing.add("REFUND_STATUS_MISMATCH");
                continue;
            }
            included.add(sale.id());
            if (sale.requestedCash().compareTo(sale.successfulCash()) != 0)
                missing.add("SUCCESSFUL_REFUND_MISMATCH");
            cashRefunded = cashRefunded.add(sale.successfulCash());
            if (Integer.valueOf(1).equals(order.getPricingVersion())) {
                if (share == null || share.pendingRows() > 0 || share.baseRows() != sale.itemCount()
                        || share.baseCash().compareTo(sale.requestedCash()) != 0)
                    missing.add("REFUND_ALLOCATION_MISSING_OR_PENDING");
                else {
                    reversal = reversal.add(share.cashPlatform());
                    costReversal = costReversal.add(share.costPlatform());
                }
            }
        }
        if (cashRefunded.compareTo(cashPaid) > 0
                || reversal.compareTo(promotion.cashPlatform()) > 0
                || costReversal.compareTo(promotion.costPlatform()) > 0)
            missing.add("SOURCE_AMOUNT_NOT_CONSERVED");
        BigDecimal payable = cashPaid.subtract(cashRefunded).add(promotion.cashPlatform())
                .subtract(reversal).add(promotion.costPlatform()).subtract(costReversal);
        if (payable.signum() < 0)
            missing.add("NEGATIVE_PAYABLE");
        String state = !missing.isEmpty() ? "REVIEW_REQUIRED" : payable.signum() == 0 ? "NO_PAYMENT" : "PAYABLE";
        return new Quote(new SettlementView.Candidate(order.getId(), order.getShopId(),
                order.getShopOwnerUserIdAtOrder(), due, state,
                missing.isEmpty() ? payable : null, List.copyOf(missing)), cashPaid, cashRefunded,
                promotion.cashPlatform(), reversal, promotion.costPlatform(), costReversal,
                hash(sourceParts), List.copyOf(included));
    }

    private Quote notPayable(Order order, LocalDateTime due, String state, List<String> missing) {
        return new Quote(new SettlementView.Candidate(order.getId(), order.getShopId(),
                order.getShopOwnerUserIdAtOrder(), due, state, null, List.copyOf(missing)),
                ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, null, List.of());
    }

    private void revalidate(ManualSettlementMapper.Head head) {
        if (shops.lockById(head.shopId()) == null)
            throw new BusinessException(409, "结算店铺已不存在，请作废重建");
        accounts.requireShopOwner(head.payeeUserId(), "收款主体不存在");
        BigDecimal amount = ZERO;
        for (var line : settlements.lines(head.id())) {
            sourceLocks.lockSource(line.orderId());
            Order order = orders.selectForUpdate(line.orderId());
            if (order == null || order.getShopId() != head.shopId())
                throw new BusinessException(409, "结算订单归属已变化，请作废重建");
            Quote current = quote(order, line.ownershipEvidence());
            if (!"PAYABLE".equals(current.candidate().state())
                    || !Arrays.equals(current.sourceHash(), line.sourceHash())
                    || current.candidate().payableAmount().compareTo(line.payableAmount()) != 0)
                throw new BusinessException(409, "结算来源或金额已变化，请作废重建");
            amount = amount.add(line.payableAmount());
        }
        if (amount.signum() <= 0 || amount.compareTo(head.amount()) != 0)
            throw new BusinessException(409, "结算总额已变化，请作废重建");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SettlementView.Detail approve(long adminId, long id, SettlementRequests.Key request) {
        accounts.requireAdmin(adminId);
        var head = requireHead(id, true);
        if ("APPROVED".equals(head.status()) && request.requestKey().equals(head.approvalKey()))
            return view(id);
        if (!"DRAFT".equals(head.status()))
            throw new BusinessException(409, "结算单不能审核");
        revalidate(head);
        if (settlements.approve(id, adminId, request.requestKey(), LocalDateTime.now(businessClock)) != 1)
            throw new BusinessException(409, "结算单审核状态冲突");
        settlements.audit(adminId, "MANUAL_SETTLEMENT_APPROVED", id, "应付事实复核后通过审核",
                LocalDateTime.now(businessClock));
        return view(id);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SettlementView.Detail processing(long adminId, long id, SettlementRequests.Key request) {
        accounts.requireAdmin(adminId);
        var head = requireHead(id, true);
        if ("PROCESSING".equals(head.status()) && request.requestKey().equals(head.processingKey()))
            return view(id);
        if (!"APPROVED".equals(head.status()))
            throw new BusinessException(409, "结算单不能进入付款处理中");
        revalidate(head);
        if (settlements.processing(id, adminId, request.requestKey(), LocalDateTime.now(businessClock)) != 1)
            throw new BusinessException(409, "结算单付款状态冲突");
        settlements.audit(adminId, "MANUAL_SETTLEMENT_PROCESSING", id, "应付金额与收款主体已冻结",
                LocalDateTime.now(businessClock));
        return view(id);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SettlementView.Detail payment(long adminId, long id, SettlementRequests.Payment request) {
        accounts.requireAdmin(adminId);
        var head = requireHead(id, true);
        byte[] requestHash = hash(request.payeeUserId().toString(), request.actualAmount().setScale(2).toPlainString(),
                request.paidAt().toString(), request.method(), Objects.toString(request.externalReference()),
                Objects.toString(request.proofReference()));
        if ("PAID".equals(head.status()) && request.requestKey().equals(head.paymentKey())) {
            if (!Arrays.equals(head.paymentHash(), requestHash))
                throw new BusinessException(409, "付款登记请求键内容冲突");
            return view(id);
        }
        if (!"PROCESSING".equals(head.status()))
            throw new BusinessException(409, "结算单未进入付款处理中");
        if (request.payeeUserId() != head.payeeUserId())
            throw new BusinessException(409, "实际收款主体与冻结归属不一致");
        if (!hasText(request.externalReference()) && !hasText(request.proofReference()))
            throw new BusinessException(400, "须填写外部流水或有效付款凭证");
        if (request.paidAt().isAfter(LocalDateTime.now(businessClock)))
            throw new BusinessException(400, "付款时间不能晚于当前时间");
        if (settlements.paid(id, adminId, request.requestKey(), requestHash, request.actualAmount(),
                request.paidAt(), request.method().trim(), trim(request.externalReference()),
                trim(request.proofReference())) != 1)
            throw new BusinessException(409, "付款登记状态冲突");
        settlements.audit(adminId, "MANUAL_SETTLEMENT_PAID_RECORDED", id,
                "管理员登记线下付款，未核验渠道到账", LocalDateTime.now(businessClock));
        BigDecimal difference = request.actualAmount().subtract(head.amount());
        if (difference.signum() != 0)
            addAdjustment(id, null, null, "PAYMENT_DIFFERENCE", hash("PAYMENT_DIFFERENCE", difference.toPlainString()),
                    "AMOUNT", difference, null, null, null, null,
                    "实际付款额与冻结应付额不一致，待人工核对",
                    hasText(request.proofReference()) ? request.proofReference() : request.externalReference(), adminId);
        syncRefunds(requireHead(id, false), adminId);
        return view(id);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SettlementView.Detail voidSettlement(long adminId, long id, SettlementRequests.Void request) {
        accounts.requireAdmin(adminId);
        var head = requireHead(id, true);
        if ("VOID".equals(head.status()) && request.requestKey().equals(head.voidKey())) {
            if (!request.reason().trim().equals(head.voidReason())
                    || !request.noPaymentEvidence().trim().equals(head.noPaymentEvidence()))
                throw new BusinessException(409, "作废请求键内容冲突");
            return view(id);
        }
        if ("PAID".equals(head.status()) || "VOID".equals(head.status()))
            throw new BusinessException(409, "已付款或已作废结算单不能再次作废");
        if (settlements.voidSettlement(id, adminId, request.requestKey(), request.reason().trim(),
                request.noPaymentEvidence().trim(), LocalDateTime.now(businessClock)) != 1)
            throw new BusinessException(409, "结算单作废状态冲突");
        settlements.releaseLines(id);
        settlements.audit(adminId, "MANUAL_SETTLEMENT_VOID", id,
                "已核实未实际付款后作废并释放订单占用", LocalDateTime.now(businessClock));
        return view(id);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SettlementView.Detail correction(long adminId, long id, SettlementRequests.Correction request) {
        accounts.requireAdmin(adminId);
        var head = requireHead(id, true);
        if (!"PAID".equals(head.status()))
            throw new BusinessException(409, "仅已登记付款单可追加更正");
        switch (request.kind()) {
            case "AMOUNT" -> {
                if (request.amountDelta() == null || request.amountDelta().signum() == 0
                        || request.proposedPayeeUserId() != null || hasText(request.replacementMethod())
                        || hasText(request.replacementExternalReference()) || hasText(request.replacementProofReference()))
                    throw new BusinessException(400, "金额调整内容无效");
            }
            case "PAYEE" -> {
                if (request.proposedPayeeUserId() == null || request.proposedPayeeUserId() == head.payeeUserId()
                        || request.amountDelta() != null || hasText(request.replacementMethod())
                        || hasText(request.replacementExternalReference()) || hasText(request.replacementProofReference()))
                    throw new BusinessException(400, "主体调整内容无效");
                accounts.requireShopOwner(request.proposedPayeeUserId(), "收款主体不存在");
            }
            case "EVIDENCE" -> {
                if (request.amountDelta() != null || request.proposedPayeeUserId() != null
                        || (!hasText(request.replacementMethod())
                        && !hasText(request.replacementExternalReference())
                        && !hasText(request.replacementProofReference())))
                    throw new BusinessException(400, "凭证更正内容无效");
            }
            default -> throw new BusinessException(400, "更正类型无效");
        }
        byte[] digest = hash(request.kind(), Objects.toString(request.amountDelta()),
                Objects.toString(request.proposedPayeeUserId()), Objects.toString(request.replacementMethod()),
                Objects.toString(request.replacementExternalReference()),
                Objects.toString(request.replacementProofReference()), request.reason(), request.evidenceReference());
        var previous = settlements.adjustmentByKey(id, request.requestKey());
        if (previous != null) {
            if (!Arrays.equals(previous.requestHash(), digest))
                throw new BusinessException(409, "更正请求键内容冲突");
            return view(id);
        }
        addAdjustment(id, null, null, request.requestKey(), digest, request.kind(),
                request.amountDelta() == null ? ZERO : request.amountDelta(), request.proposedPayeeUserId(),
                trim(request.replacementMethod()), trim(request.replacementExternalReference()),
                trim(request.replacementProofReference()), request.reason().trim(),
                request.evidenceReference().trim(), adminId);
        return view(id);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SettlementView.Detail reviewAdjustment(long adminId, long id, long adjustmentId,
                                                   SettlementRequests.Review request) {
        accounts.requireAdmin(adminId);
        var head = requireHead(id, true);
        if (!"PAID".equals(head.status()))
            throw new BusinessException(409, "结算单尚未登记付款");
        var adjustment = settlements.adjustment(adjustmentId);
        if (adjustment == null || adjustment.settlementId() != id)
            throw new BusinessException(404, "调整记录不存在");
        if ("REVIEWED".equals(adjustment.status())) {
            if (!request.requestKey().equals(adjustment.reviewKey())
                    || !request.evidenceReference().trim().equals(adjustment.reviewEvidenceReference()))
                throw new BusinessException(409, "调整记录已核对");
            return view(id);
        }
        if (settlements.reviewAdjustment(adjustmentId, adminId, request.requestKey(),
                request.evidenceReference().trim(), LocalDateTime.now(businessClock)) != 1)
            throw new BusinessException(409, "调整记录核对状态冲突");
        settlements.audit(adminId, "MANUAL_SETTLEMENT_ADJUSTMENT_REVIEWED", id,
                "人工调整已核对，未自动执行资金抵扣", LocalDateTime.now(businessClock));
        return view(id);
    }

    private void syncRefunds(ManualSettlementMapper.Head head, long adminId) {
        for (var line : settlements.lines(head.id())) {
            sourceLocks.lockSource(line.orderId());
            orders.selectForUpdate(line.orderId());
            var refunds = afterSales.cases(line.orderId());
            Map<Long, MarketingSettlementFacts.Reversal> reversals = new HashMap<>();
            for (var reversal : marketing.snapshot(line.orderId()).reversals())
                reversals.put(reversal.caseId(), reversal);
            for (var sale : refunds) {
                if (!"REFUNDED".equals(sale.status()) || settlements.includedRefund(line.id(), sale.id()))
                    continue;
                var share = reversals.get(sale.id());
                boolean complete = sale.unresolvedAttempts() == 0
                        && sale.requestedCash().compareTo(sale.successfulCash()) == 0
                        && share != null && share.pendingRows() == 0 && share.baseRows() == sale.itemCount()
                        && share.baseCash().compareTo(sale.requestedCash()) == 0;
                BigDecimal delta = complete ? sale.successfulCash().add(share.cashPlatform())
                        .add(share.costPlatform()).negate() : ZERO;
                String reason = complete ? "登记付款后出现来源退款，待人工处理"
                        : "登记付款后退款来源证据不完整，待人工核对";
                addAdjustment(head.id(), line.id(), sale.id(), "REFUND:" + sale.id(),
                        hash("REFUND", Long.toString(sale.id())), "REFUND", delta, null,
                        null, null, null, reason, "AFTER_SALE:" + sale.id(), adminId);
            }
        }
    }

    private void addAdjustment(long settlementId, Long lineId, Long caseId, String key, byte[] digest,
                               String kind, BigDecimal delta, Long payee, String method, String external,
                               String proof, String reason, String evidence, long adminId) {
        if (settlements.adjustmentByKey(settlementId, key) != null)
            return;
        settlements.addAdjustment(settlementId, lineId, caseId, key, digest, kind, delta, payee,
                method, external, proof, reason, evidence, adminId);
        settlements.audit(adminId, "MANUAL_SETTLEMENT_ADJUSTMENT_CREATED", settlementId,
                "新增人工待核对调整：" + kind, LocalDateTime.now(businessClock));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trim(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private int pageSize(int size) {
        if (size < 1 || size > 50)
            throw new BusinessException(400, "分页大小须在 1 到 50 之间");
        return size;
    }

    private static String jsonIds(List<Long> ids) {
        return "[" + String.join(",", ids.stream().map(String::valueOf).toList()) + "]";
    }

    private static byte[] hash(List<String> parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] value = Objects.toString(part, "<null>").getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(value.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(value);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] hash(String... parts) {
        return hash(Arrays.asList(parts));
    }

    private ManualSettlementMapper.Head requireHead(long id, boolean lock) {
        var head = settlements.head(id, lock);
        if (head == null)
            throw new BusinessException(404, "结算单不存在");
        return head;
    }

    private SettlementView.Detail view(long id) {
        var h = requireHead(id, false);
        var lines = settlements.lines(id).stream().map(l -> new SettlementView.Line(l.id(), l.orderId(),
                l.ownerUserIdAtOrder(), l.ownershipEvidence(), l.cashPaid(), l.cashRefunded(),
                l.platformSubsidy(), l.platformReversal(), l.costPlatform(), l.costReversal(),
                l.payableAmount())).toList();
        var adjustments = settlements.adjustments(id).stream().map(a -> new SettlementView.Adjustment(a.id(),
                a.lineId(), a.sourceCaseId(), a.kind(), a.amountDelta(), a.proposedPayeeUserId(),
                a.status(), a.reason(), a.evidenceReference(), a.createdAt(), a.reviewedAt(),
                a.reviewEvidenceReference(), a.replacementMethod(), a.replacementExternalReference(),
                a.replacementProofReference())).toList();
        return new SettlementView.Detail(h.id(), h.shopId(), h.payeeUserId(), h.status(), h.amount(),
                h.paidAmount(), h.paidAt(), h.paymentMethod(), h.externalReference(), h.proofReference(),
                h.createdAt(), lines, adjustments, settlements.audit(id));
    }
}
