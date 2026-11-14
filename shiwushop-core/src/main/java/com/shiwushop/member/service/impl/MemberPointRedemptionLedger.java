package com.shiwushop.member.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.member.mapper.MemberMapper;
import com.shiwushop.member.mapper.MemberPointRedemptionMapper;
import com.shiwushop.member.model.MemberPointEntry;
import com.shiwushop.member.model.MemberPointLot;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.member.service.MemberPointRedemptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** 交易订单已创建且调用方持有订单锁时，按原批次扣兑与返还积分。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MemberPointRedemptionLedger implements MemberPointRedemptionService {
    private record LinkedAllocation(long lotId, long originalId, String kind, int ordinal, long units) {
    }

    private final MemberMapper mapper;
    private final MemberPointRedemptionMapper redemption;
    private final MemberAccountService accounts;
    private final MemberPointLedger points;
    private final Clock businessClock;

    @Override
    public long spend(long userId, long orderId, long units) {
        if (units <= 0)
            throw new IllegalArgumentException("兑换积分必须为正");
        var account = accounts.initializeAndLock(userId);
        String key = "POINTS:SPEND:" + orderId;
        var previous = mapper.pointEntry(key);
        if (previous != null) {
            if (!"SPEND".equals(previous.getEventKind()) || !previous.getUserId().equals(userId)
                    || !previous.getOrderId().equals(orderId)
                    || previous.getAvailableDelta() != -units)
                throw conflict("IDEMPOTENCY_CONFLICT", "同一订单对应不同积分兑换");
            return previous.getId();
        }
        var now = now();
        for (var lot : mapper.expiredLots(userId, now))
            points.expire(account, lot, null, now);
        if (account.getPointDebtUnits() != 0)
            throw conflict("POINTS_DEBT_OUTSTANDING", "积分欠额未清");
        if (account.getAvailablePointUnits() < units)
            throw conflict("POINTS_NOT_ENOUGH", "可用积分不足");
        var lots = mapper.availableLots(userId, now);
        long remaining = units;
        for (var lot : lots)
            remaining -= Math.min(remaining, lot.getAvailableUnits());
        if (remaining != 0)
            throw new IllegalStateException("账户可用积分与来源批次不一致");
        var entry = points.change(account, "SPEND", key, orderId, null, -units, 0);
        remaining = units;
        for (var lot : lots) {
            long take = Math.min(remaining, lot.getAvailableUnits());
            if (take == 0)
                continue;
            lot.setAvailableUnits(lot.getAvailableUnits() - take);
            lot.setSpentUnits(Math.addExact(lot.getSpentUnits(), take));
            if (lot.getAvailableUnits() == 0 && lot.getHeldUnits() == 0)
                lot.setStatus("EXHAUSTED");
            mapper.updateLot(lot);
            mapper.allocate(entry.getId(), lot.getId(), "SPEND", take);
            remaining -= take;
            if (remaining == 0)
                break;
        }
        return entry.getId();
    }

    @Override
    public long returnSpent(long userId, long orderId, Long afterSaleCaseId, long units,
                            String returnKey, boolean platformFailure) {
        if (units <= 0)
            throw new IllegalArgumentException("返还积分必须为正");
        if (returnKey == null || !returnKey.matches("[!-~]{1,64}"))
            throw new IllegalArgumentException("返还请求键须为1至64位可见ASCII");
        var account = accounts.initializeAndLock(userId);
        String key = "POINTS:RETURN:" + orderId + ":" + returnKey;
        var previous = mapper.pointEntry(key);
        if (previous != null) {
            if (!"RELEASE".equals(previous.getEventKind()) || !previous.getUserId().equals(userId)
                    || !previous.getOrderId().equals(orderId)
                    || !java.util.Objects.equals(previous.getAfterSaleCaseId(), afterSaleCaseId)
                    || !previous.getReason().equals(platformFailure ? "PLATFORM_FAILURE" : "NORMAL_RETURN")
                    || redemption.returnedUnits(previous.getId()) != units)
                throw conflict("IDEMPOTENCY_CONFLICT", "返还请求键对应不同兑换");
            return previous.getId();
        }
        var sourceKey = "POINTS:SPEND:" + orderId;
        var source = mapper.pointEntry(sourceKey);
        if (source == null || !"SPEND".equals(source.getEventKind()) || !source.getUserId().equals(userId)
                || !source.getOrderId().equals(orderId))
            throw conflict("POINT_REDEMPTION_NOT_FOUND", "兑换积分来源不存在");
        var allocations = redemption.spendAllocations(sourceKey);
        long refundable = 0;
        for (var allocation : allocations) {
            if (allocation.returnedUnits() > allocation.units())
                throw new IllegalStateException("兑换积分已返还数量超过原消费");
            refundable = Math.addExact(refundable, allocation.units() - allocation.returnedUnits());
        }
        if (units > refundable)
            throw conflict("POINT_RETURN_EXCEEDS_SPEND", "返还积分超过尚未返还的兑换金额");
        long remaining = units;
        long live = 0;
        long expired = 0;
        long debtCleared = 0;
        var now = now();
        var links = new ArrayList<LinkedAllocation>();
        var returnOrdinals = new HashMap<Long, Integer>();
        var repayOrdinals = new HashMap<Long, Integer>();
        for (var allocation : allocations) {
            long take = Math.min(remaining, allocation.units() - allocation.returnedUnits());
            if (take == 0)
                continue;
            var lot = redemption.lockLot(allocation.lotId());
            if (lot == null || !lot.getUserId().equals(userId))
                throw MemberPointLedger.provenanceConflict(allocation.lotId());
            var debts = redemption.debtSources(allocation.id());
            long debtRemaining = 0;
            for (var debt : debts) {
                if (!debt.lotId().equals(lot.getId()) || !debt.originalId().equals(allocation.id()))
                    throw MemberPointLedger.provenanceConflict(lot.getId());
                long restored = 0;
                for (var offset : redemption.debtOffsets(debt.id())) {
                    if (offset.repaidUnits() + offset.reversedUnits() > offset.units())
                        throw MemberPointLedger.provenanceConflict(offset.lotId());
                    restored = Math.addExact(restored, offset.repaidUnits());
                }
                if (debt.offsetUnits() + debt.repaidUnits() > debt.units() || restored > debt.offsetUnits())
                    throw MemberPointLedger.provenanceConflict(lot.getId());
                debtRemaining = Math.addExact(debtRemaining, debt.units() - debt.repaidUnits() - restored);
            }
            if (debtRemaining > allocation.units() - allocation.returnedUnits())
                throw MemberPointLedger.provenanceConflict(lot.getId());
            long debtTake = Math.min(take, debtRemaining);
            long spentTake = take - debtTake;
            if (lot.getSpentUnits() < spentTake)
                throw MemberPointLedger.provenanceConflict(lot.getId());
            long debtToApply = debtTake;
            for (var debt : debts) {
                if (debtToApply == 0)
                    break;
                var offsets = redemption.debtOffsets(debt.id());
                long restored = offsets.stream().mapToLong(MemberPointRedemptionMapper.DebtOffset::repaidUnits).sum();
                long debtPart = Math.min(debtToApply, debt.units() - debt.repaidUnits() - restored);
                long direct = Math.min(debtPart, debt.units() - debt.offsetUnits() - debt.repaidUnits());
                if (direct > 0) {
                    debtCleared = Math.addExact(debtCleared, direct);
                    links.add(new LinkedAllocation(lot.getId(), debt.id(), "DEBT_REPAY",
                            repayOrdinals.merge(lot.getId(), 1, Integer::sum), direct));
                }
                long restore = debtPart - direct;
                for (var offset : offsets) {
                    long amount = Math.min(restore, offset.units() - offset.repaidUnits() - offset.reversedUnits());
                    if (amount == 0)
                        continue;
                    var later = redemption.lockLot(offset.lotId());
                    if (later == null || !later.getUserId().equals(userId) || later.getDebtOffsetUnits() < amount)
                        throw MemberPointLedger.provenanceConflict(offset.lotId());
                    later.setDebtOffsetUnits(later.getDebtOffsetUnits() - amount);
                    if (now.isBefore(later.getExpiresAt())) {
                        later.setAvailableUnits(Math.addExact(later.getAvailableUnits(), amount));
                        later.setStatus("ACTIVE");
                        live = Math.addExact(live, amount);
                    } else {
                        later.setExpiredUnits(Math.addExact(later.getExpiredUnits(), amount));
                        later.setStatus(later.getAvailableUnits() == 0 && later.getHeldUnits() == 0 ? "EXPIRED" : "ACTIVE");
                    }
                    mapper.updateLot(later);
                    links.add(new LinkedAllocation(later.getId(), offset.id(), "DEBT_REPAY",
                            repayOrdinals.merge(later.getId(), 1, Integer::sum), amount));
                    restore -= amount;
                    if (restore == 0)
                        break;
                }
                if (restore != 0)
                    throw MemberPointLedger.provenanceConflict(lot.getId());
                debtToApply -= debtPart;
            }
            if (debtToApply != 0)
                throw MemberPointLedger.provenanceConflict(lot.getId());
            lot.setSpentUnits(lot.getSpentUnits() - spentTake);
            if (now.isBefore(lot.getExpiresAt())) {
                lot.setAvailableUnits(Math.addExact(lot.getAvailableUnits(), spentTake));
                lot.setStatus(lot.getAvailableUnits() == 0 && lot.getHeldUnits() == 0 ? "EXHAUSTED" : "ACTIVE");
                live = Math.addExact(live, spentTake);
            } else {
                lot.setExpiredUnits(Math.addExact(lot.getExpiredUnits(), spentTake));
                lot.setStatus(lot.getAvailableUnits() == 0 && lot.getHeldUnits() == 0 ? "EXPIRED" : "ACTIVE");
                expired = Math.addExact(expired, spentTake);
            }
            mapper.updateLot(lot);
            links.add(new LinkedAllocation(lot.getId(), allocation.id(), "RETURN",
                    returnOrdinals.merge(lot.getId(), 1, Integer::sum), take));
            remaining -= take;
            if (remaining == 0)
                break;
        }
        if (debtCleared > account.getPointDebtUnits())
            throw MemberPointLedger.provenanceConflict(allocations.getFirst().lotId());
        var entry = new MemberPointEntry();
        entry.setUserId(userId);
        entry.setEventKind("RELEASE");
        entry.setOperationKey(key);
        entry.setOrderId(orderId);
        entry.setAfterSaleCaseId(afterSaleCaseId);
        entry.setReason(platformFailure ? "PLATFORM_FAILURE" : "NORMAL_RETURN");
        entry.setAvailableDelta(live);
        entry.setDebtDelta(-debtCleared);
        points.record(account, entry);
        for (var link : links)
            redemption.allocateLinked(entry.getId(), link.lotId(), link.originalId(), link.kind(),
                    link.ordinal(), link.units());
        if (platformFailure && expired > 0) {
            long compOffset = Math.min(expired, account.getPointDebtUnits());
            var compensation = new MemberPointLot();
            compensation.setUserId(userId);
            compensation.setSourceKey(key + ":COMP");
            compensation.setIssuedUnits(expired);
            compensation.setAvailableUnits(expired - compOffset);
            compensation.setDebtOffsetUnits(compOffset);
            compensation.setEarnedAt(now);
            compensation.setExpiresAt(now.plusDays(30));
            compensation.setStatus(expired == compOffset ? "EXHAUSTED" : "ACTIVE");
            mapper.insertLot(compensation);
            var compEntry = new MemberPointEntry();
            compEntry.setUserId(userId);
            compEntry.setEventKind("COMPENSATE");
            compEntry.setOperationKey(key + ":COMP");
            compEntry.setOrderId(orderId);
            compEntry.setAfterSaleCaseId(afterSaleCaseId);
            compEntry.setReason("PLATFORM_FAILURE_EXPIRED_POINTS");
            compEntry.setAvailableDelta(expired - compOffset);
            compEntry.setDebtDelta(-compOffset);
            points.record(account, compEntry);
            if (expired > compOffset)
                mapper.allocate(compEntry.getId(), compensation.getId(), "COMPENSATE", expired - compOffset);
            if (compOffset > 0)
                points.allocateDebtOffset(userId, compEntry.getId(), compensation.getId(), compOffset,
                        Math.addExact(account.getPointDebtUnits(), compOffset));
        }
        return entry.getId();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(businessClock).truncatedTo(ChronoUnit.MILLIS);
    }

    private static BusinessException conflict(String reason, String message) {
        return new BusinessException(409, message, Map.of("reason", reason), null);
    }
}
