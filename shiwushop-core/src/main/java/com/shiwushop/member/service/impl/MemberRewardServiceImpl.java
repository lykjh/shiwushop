package com.shiwushop.member.service.impl;

import com.shiwushop.member.MemberSettings;
import com.shiwushop.member.dto.RewardLine;
import com.shiwushop.member.mapper.MemberMapper;
import com.shiwushop.member.model.*;
import com.shiwushop.member.service.MemberRewardService;
import com.shiwushop.member.service.MemberAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** 订单锁之后取得账户锁；积分批次、账本、成长历史及任务随调用方事务共同提交。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MemberRewardServiceImpl implements MemberRewardService {
    private final MemberMapper mapper;
    private final MemberSettings settings;
    private final Clock businessClock;
    private final MemberAccountService accounts;
    private final MemberPointLedger points;
    private final MemberGrowthLedger growth;

    @Override
    public String prepareOrder(Long userId) {
        return settings.enabled() ? initializeAndLock(userId).getLevelCode() : null;
    }

    @Override
    public void orderCreated(Long userId, Long ownerAtOrder, LocalDateTime createdAt, List<RewardLine> lines) {
        if (!settings.enabled())
            return;
        Objects.requireNonNull(ownerAtOrder, "新订单必须有店主快照");
        // 自店购买按下单时店主快照排除，之后转店不能改变旧订单权益资格。
        String orderReason = createdAt.isBefore(settings.rewardStartAt()) ? "LEGACY_ORDER"
                : userId.equals(ownerAtOrder) ? "SELF_SHOP_OWNER" : null;
        for (var line : lines) {
            Long itemId = line.orderItemId();
            String reason =
                    orderReason != null ? orderReason : line.paidAmount().signum() == 0 ? "NON_CASH_ITEM" : null;
            mapper.insertTask(userId, itemId, "POINTS", reason == null ? "WAIT_PAYMENT" : "EXCLUDED", reason,
                    createdAt);
            mapper.insertTask(userId, itemId, "GROWTH", reason == null ? "WAIT_RECEIPT" : "EXCLUDED", reason,
                    createdAt);
        }
    }

    @Override
    public void paymentConfirmed(Long userId, Long orderId, LocalDateTime paidAt, List<RewardLine> lines) {
        if (!settings.enabled())
            return;
        MemberAccount account = initializeAndLock(userId);
        LocalDateTime now = LocalDateTime.now(businessClock);
        for (RewardLine line : lines) {
            MemberRewardTask task = mapper.task(line.orderItemId(), "POINTS");
            // 历史订单没有会员任务，不能因补偿扫描追溯发放。
            if (task == null || "EXCLUDED".equals(task.status()))
                continue;
            if (!"APPLIED".equals(task.status())) {
                long units = MemberRewardPolicy.pointUnits(line.paidAmount());
                if (units <= 0)
                    throw new IllegalStateException("现金权益任务必须有正实付金额");
                MemberPointLot lot = new MemberPointLot();
                lot.setUserId(userId);
                lot.setOrderItemId(line.orderItemId());
                lot.setSourceKey("ORDER_POINTS:" + line.orderItemId());
                lot.setIssuedUnits(units);
                // 后续入账先抵偿退款欠额，剩余部分才能成为可用积分。
                long offset = Math.min(units, account.getPointDebtUnits());
                lot.setDebtOffsetUnits(offset);
                lot.setAvailableUnits(units - offset);
                lot.setEarnedAt(now);
                lot.setExpiresAt(MemberRewardPolicy.pointsExpireAt(now));
                lot.setStatus(lot.getAvailableUnits() == 0 ? "EXHAUSTED" : "ACTIVE");
                mapper.insertLot(lot);
                MemberPointEntry entry = points.change(account, "EARN", lot.getSourceKey(), orderId, null,
                        units - offset, -offset);
                if (units > offset)
                    mapper.allocate(entry.getId(), lot.getId(), "EARN", units - offset);
                if (offset > 0)
                    points.allocateDebtOffset(userId, entry.getId(), lot.getId(), offset,
                            Math.addExact(account.getPointDebtUnits(), offset));
                mapper.applied(task.id(), paidAt, BigDecimal.ZERO, units);
            }
            // 漏发补偿与已确认退款在同一个订单锁及事务中完成，不暴露临时可用窗口。
            reversePoints(account, orderId, null, line, now);
        }
    }

    @Override
    public void receiptConfirmed(Long orderId, LocalDateTime confirmedAt) {
        if (settings.enabled())
            mapper.receipt(orderId);
    }

    @Override
    public void growthDue(Long userId, Long orderId, LocalDateTime confirmedAt,
                          boolean unresolvedAfterSale, List<RewardLine> lines) {
        if (!settings.enabled())
            return;
        LocalDateTime now = LocalDateTime.now(businessClock);
        if (confirmedAt == null || now.isBefore(MemberRewardPolicy.growthEligibleAt(confirmedAt)))
            return;
        MemberAccount account = initializeAndLock(userId);
        for (RewardLine line : lines) {
            MemberRewardTask task = mapper.task(line.orderItemId(), "GROWTH");
            if (task == null || "APPLIED".equals(task.status()) || "EXCLUDED".equals(task.status()))
                continue;
            // 售后未决时延后成长入账，避免将尚不确定留存的实付计入等级。
            if (unresolvedAfterSale) {
                mapper.waitAfterSale(task.id(), now.plusMinutes(1));
                continue;
            }
            BigDecimal retained = line.retainedAmount();
            if (retained.signum() == 0) {
                mapper.excludeRefund(task.id());
                continue;
            }
            if (retained.signum() < 0)
                throw new IllegalStateException("已退款超过原项实付");
            growthChange(account, line.orderItemId(), null, null, "EARN", "ORDER_GROWTH:" + line.orderItemId(),
                    retained, now);
            mapper.applied(task.id(), MemberRewardPolicy.growthEligibleAt(confirmedAt), retained, 0);
        }
    }

    @Override
    public void refundConfirmed(Long userId, Long orderId, Long caseId, List<RewardLine> lines) {
        if (!settings.enabled())
            return;
        MemberAccount account = initializeAndLock(userId);
        LocalDateTime now = LocalDateTime.now(businessClock);
        for (RewardLine line : lines) {
            reversePoints(account, orderId, caseId, line, now);
            MemberGrowthEntry source = mapper.growthEntry("ORDER_GROWTH:" + line.orderItemId());
            if (source == null)
                continue;
            String key = "REFUND_GROWTH:" + caseId + ":" + line.orderItemId();
            if (mapper.growthEntry(key) != null)
                continue;
            // 人工扣减与退款共享原入账来源，退款只补扣尚未冲回的差额。
            BigDecimal reverse = MemberRewardPolicy.additionalGrowthReversal(source.getDeltaAmount(),
                    line.retainedAmount(), mapper.reversedGrowth(source.getId()));
            if (reverse.signum() > 0)
                growthChange(account, line.orderItemId(), caseId, source.getId(), "REFUND", key, reverse.negate(), now);
        }
    }

    private MemberAccount initializeAndLock(Long userId) {
        return accounts.initializeAndLock(userId);
    }

    private void reversePoints(MemberAccount account, Long orderId, Long caseId, RewardLine line, LocalDateTime now) {
        MemberPointLot lot = mapper.lot("ORDER_POINTS:" + line.orderItemId());
        if (lot == null)
            return;
        points.expire(account, lot, orderId, now);
        long refunded = MemberRewardPolicy.pointUnits(line.refundedAmount());
        // 已到期回收的积分不再次形成退款欠额，重复回调只处理累计新增差额。
        long reverse = Math.max(0, refunded - lot.getReversedUnits() - lot.getExpiredUnits());
        if (reverse == 0)
            return;
        if (refunded > lot.getIssuedUnits())
            throw new IllegalStateException("退款积分超过原发放");
        String key = caseId == null ? "RECOVER_REFUND_POINTS:" + line.orderItemId() + ":" + refunded
                : "REFUND_POINTS:" + caseId + ":" + line.orderItemId();
        if (mapper.pointEntryId(key) != null)
            return;
        long available = Math.min(reverse, lot.getAvailableUnits());
        // 已花掉的来源积分不能直接扣回可用余额，记录欠额等待以后入账抵偿。
        long debt = reverse - available;
        if (debt > lot.getSpentUnits() + lot.getDebtOffsetUnits())
            throw new IllegalStateException("来源积分仍被占用，须先核对兑换占用");
        long spent = Math.min(debt, lot.getSpentUnits());
        long reopened = debt - spent;
        long spentBefore = lot.getSpentUnits();
        long offsetBefore = lot.getDebtOffsetUnits();
        lot.setAvailableUnits(lot.getAvailableUnits() - available);
        lot.setSpentUnits(lot.getSpentUnits() - spent);
        lot.setDebtOffsetUnits(lot.getDebtOffsetUnits() - reopened);
        lot.setReversedUnits(lot.getReversedUnits() + reverse);
        if (lot.getAvailableUnits() == 0 && lot.getHeldUnits() == 0 && !"EXPIRED".equals(lot.getStatus()))
            lot.setStatus("EXHAUSTED");
        mapper.updateLot(lot);
        MemberPointEntry entry = points.change(account, "REFUND_REVERSE", key, orderId, caseId, -available, debt);
        mapper.allocate(entry.getId(), lot.getId(), "REVERSE", reverse);
        if (spent > 0)
            points.allocateDebtCreate(entry.getId(), lot.getId(), spent, spentBefore);
        if (reopened > 0)
            points.reverseDebtOffsets(entry.getId(), lot.getId(), reopened, offsetBefore);
    }

    private void growthChange(MemberAccount account, Long itemId, Long caseId, Long sourceId,
                              String kind, String key, BigDecimal delta, LocalDateTime now) {
        MemberGrowthEntry entry = new MemberGrowthEntry();
        entry.setUserId(account.getUserId());
        entry.setOrderItemId(itemId);
        entry.setAfterSaleCaseId(caseId);
        entry.setSourceEntryId(sourceId);
        entry.setEventKind(kind);
        entry.setOperationKey(key);
        entry.setDeltaAmount(delta);
        growth.record(account, entry, now);
    }
}
