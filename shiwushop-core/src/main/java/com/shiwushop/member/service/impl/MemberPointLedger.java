package com.shiwushop.member.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.member.mapper.MemberMapper;
import com.shiwushop.member.mapper.MemberPointRedemptionMapper;
import com.shiwushop.member.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/** 入账、退款和过期共用一处账本写入；调用方已经持有账户锁。 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MemberPointLedger {
    private final MemberMapper mapper;
    private final MemberPointRedemptionMapper redemption;

    /** 账户锁下按欠额来源ID分配，旧的无来源欠额交人工核对。 */
    public void allocateDebtOffset(long userId, long entryId, long lotId, long units, long expectedDebtBefore) {
        long remaining = units;
        int ordinal = 1;
        var debts = redemption.accountDebts(userId);
        long totalOpen = 0;
        for (var debt : debts) {
            long open = debt.units() - debt.offsetUnits() - debt.repaidUnits();
            if (open < 0 || (open > 0 && debt.originalId() == null))
                throw provenanceConflict(lotId);
            totalOpen = Math.addExact(totalOpen, open);
        }
        if (totalOpen != expectedDebtBefore)
            throw provenanceConflict(lotId);
        for (var debt : debts) {
            long open = debt.units() - debt.offsetUnits() - debt.repaidUnits();
            long take = Math.min(remaining, open);
            if (take > 0) {
                redemption.allocateLinked(entryId, lotId, debt.id(), "DEBT_OFFSET", ordinal++, take);
                remaining -= take;
            }
            if (remaining == 0)
                break;
        }
        if (remaining != 0)
            throw provenanceConflict(lotId);
    }

    /** 退款只把仍未返还、未冲回的原消费分配转成对应来源欠额。 */
    public void allocateDebtCreate(long entryId, long lotId, long units, long expectedSpentBefore) {
        long remaining = units;
        int ordinal = 1;
        var spends = redemption.lotSpends(lotId);
        long totalOpen = 0;
        for (var spend : spends) {
            long created = redemption.debtSources(spend.id()).stream().mapToLong(
                    MemberPointRedemptionMapper.DebtSource::units).sum();
            long open = spend.units() - spend.returnedUnits() - created;
            if (open < 0)
                throw provenanceConflict(lotId);
            totalOpen = Math.addExact(totalOpen, open);
        }
        if (totalOpen != expectedSpentBefore)
            throw provenanceConflict(lotId);
        for (var spend : spends) {
            long created = redemption.debtSources(spend.id()).stream().mapToLong(
                    MemberPointRedemptionMapper.DebtSource::units).sum();
            long open = spend.units() - spend.returnedUnits() - created;
            long take = Math.min(remaining, open);
            if (take > 0) {
                redemption.allocateLinked(entryId, lotId, spend.id(), "DEBT_CREATE", ordinal++, take);
                remaining -= take;
            }
            if (remaining == 0)
                break;
        }
        if (remaining != 0)
            throw provenanceConflict(lotId);
    }

    /** 后续批次自身退款时撤销其抵债分配，使原欠额重新可追溯地开放。 */
    public void reverseDebtOffsets(long entryId, long lotId, long units, long expectedOffsetBefore) {
        long remaining = units;
        int ordinal = 1;
        var offsets = redemption.lotDebtOffsets(lotId);
        long totalOpen = 0;
        for (var offset : offsets) {
            long open = offset.units() - offset.repaidUnits() - offset.reversedUnits();
            if (open < 0)
                throw provenanceConflict(lotId);
            totalOpen = Math.addExact(totalOpen, open);
        }
        if (totalOpen != expectedOffsetBefore)
            throw provenanceConflict(lotId);
        for (var offset : offsets) {
            long open = offset.units() - offset.repaidUnits() - offset.reversedUnits();
            long take = Math.min(remaining, open);
            if (take > 0) {
                redemption.allocateLinked(entryId, lotId, offset.id(), "REVERSE", ordinal++, take);
                remaining -= take;
            }
            if (remaining == 0)
                break;
        }
        if (remaining != 0)
            throw provenanceConflict(lotId);
    }

    static BusinessException provenanceConflict(long lotId) {
        return new BusinessException(409, "积分来源无法核对，需人工处理",
                Map.of("reason", "POINT_PROVENANCE_REQUIRES_REVIEW", "lotId", lotId), null);
    }

    public MemberPointEntry change(MemberAccount account, String kind, String key, Long orderId,
                                   Long caseId, long availableDelta, long debtDelta) {
        var entry = new MemberPointEntry();
        entry.setUserId(account.getUserId());
        entry.setEventKind(kind);
        entry.setOperationKey(key);
        entry.setOrderId(orderId);
        entry.setAfterSaleCaseId(caseId);
        entry.setAvailableDelta(availableDelta);
        entry.setDebtDelta(debtDelta);
        record(account, entry);
        return entry;
    }

    public void record(MemberAccount account, MemberPointEntry entry) {
        account.setAvailablePointUnits(Math.addExact(account.getAvailablePointUnits(), entry.getAvailableDelta()));
        account.setPointDebtUnits(Math.addExact(account.getPointDebtUnits(), entry.getDebtDelta()));
        entry.setAvailableAfter(account.getAvailablePointUnits());
        entry.setHeldAfter(account.getHeldPointUnits());
        entry.setDebtAfter(account.getPointDebtUnits());
        mapper.insertPointEntry(entry);
        mapper.updateAccount(account);
    }

    public void expire(MemberAccount account, MemberPointLot lot, Long orderId, LocalDateTime now) {
        if (now.isBefore(lot.getExpiresAt()) || lot.getAvailableUnits() == 0)
            return;
        long units = lot.getAvailableUnits();
        lot.setExpiredUnits(Math.addExact(lot.getExpiredUnits(), units));
        lot.setAvailableUnits(0);
        lot.setStatus(lot.getHeldUnits() == 0 ? "EXPIRED" : "ACTIVE");
        mapper.updateLot(lot);
        var entry = change(account, "EXPIRE", "EXPIRE:" + lot.getId() + ":" + lot.getExpiredUnits(), orderId, null,
                -units, 0);
        mapper.allocate(entry.getId(), lot.getId(), "EXPIRE", units);
    }
}
