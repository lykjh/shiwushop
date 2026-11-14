package com.shiwushop.member.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.member.MemberSettings;
import com.shiwushop.member.dto.GrowthAdjustment;
import com.shiwushop.member.dto.PointAdjustment;
import com.shiwushop.member.mapper.MemberMapper;
import com.shiwushop.member.model.MemberGrowthEntry;
import com.shiwushop.member.model.MemberPointEntry;
import com.shiwushop.member.model.MemberPointLot;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.member.service.MemberAdministrationService;
import com.shiwushop.member.vo.GrowthAdjustmentResult;
import com.shiwushop.member.vo.PointAdjustmentResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class MemberAdministrationServiceImpl implements MemberAdministrationService {
    private final MemberMapper mapper;
    private final MemberAccountService accounts;
    private final MemberGrowthLedger ledger;
    private final MemberPointLedger points;
    private final AccountSecurity security;
    private final MemberSettings settings;
    private final Clock businessClock;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public GrowthAdjustmentResult adjustGrowth(Long actorId, Long userId, String requestKey, GrowthAdjustment request) {
        security.requireAdmin(actorId);
        if (!settings.enabled())
            throw new BusinessException(404, "会员功能尚未启用");
        if (request.deltaAmount().signum() == 0)
            throw new BusinessException(400, "成长调整不能为零", Map.of("reason", "INVALID_ARGUMENT"), null);
        var account = accounts.initializeAndLock(userId);
        String key = "ADJUST_GROWTH:" + actorId + ":" + userId + ":" + requestKey;
        var previous = mapper.growthEntry(key);
        if (previous != null) {
            if (!previous.getUserId().equals(userId) || previous.getDeltaAmount().compareTo(request.deltaAmount()) != 0
                    || !Objects.equals(previous.getSourceEntryId(), request.sourceEntryId())
                    || !previous.getReason().equals(request.reason())
                    || !previous.getEvidenceReference().equals(request.evidenceRef()))
                throw new BusinessException(409, "幂等键对应的成长调整内容不同",
                        Map.of("reason", "IDEMPOTENCY_CONFLICT"), null);
            return new GrowthAdjustmentResult(previous.getId(), userId, previous.getBalanceAfter(),
                    previous.getNewLevelCode());
        }
        // 负向成长关联原正向流水，后续退款才能识别已经人工冲回的部分。
        MemberGrowthEntry source = null;
        if (request.deltaAmount().signum() < 0) {
            if (request.sourceEntryId() == null)
                throw new BusinessException(400, "负向调整必须关联原入账流水", Map.of("reason", "INVALID_ARGUMENT"),
                        null);
            source = mapper.growthEntryById(request.sourceEntryId());
            if (source == null || !source.getUserId().equals(userId) || source.getDeltaAmount().signum() <= 0)
                throw new BusinessException(404, "原入账流水不存在");
            if (request.deltaAmount().negate()
                    .compareTo(source.getDeltaAmount().subtract(mapper.reversedGrowth(source.getId()))) > 0)
                throw new BusinessException(409, "调整超过该来源尚未冲回的成长", Map.of("reason", "INVALID_STATE"),
                        null);
        } else if (request.sourceEntryId() != null) {
            throw new BusinessException(400, "正向调整不关联被冲回来源", Map.of("reason", "INVALID_ARGUMENT"), null);
        }
        var entry = new MemberGrowthEntry();
        entry.setUserId(userId);
        entry.setEventKind("ADJUST");
        entry.setOperationKey(key);
        entry.setDeltaAmount(request.deltaAmount());
        entry.setSourceEntryId(request.sourceEntryId());
        entry.setOrderItemId(source == null ? null : source.getOrderItemId());
        entry.setActorUserId(actorId);
        entry.setReason(request.reason());
        entry.setEvidenceReference(request.evidenceRef());
        var now = LocalDateTime.now(businessClock);
        ledger.record(account, entry, now);
        // 完整原因及凭据保存在同事务账本，审计只存字段定位，避免截断 500 字凭据。
        String summary = objectMapper.writeValueAsString(Map.of("entryId", entry.getId(),
                "ledger", "member_growth_entries", "reasonField", "reason", "evidenceField", "evidence_reference",
                "deltaAmount", entry.getDeltaAmount(), "balanceAfter", entry.getBalanceAfter()));
        mapper.auditAdjustment(actorId, userId, "MEMBER_GROWTH_ADJUSTED", summary, now);
        return new GrowthAdjustmentResult(entry.getId(), userId, account.getGrowthAmount(), account.getLevelCode());
    }

    @Override
    @Transactional
    public PointAdjustmentResult adjustPoints(Long actorId, Long userId, String requestKey, PointAdjustment request) {
        security.requireAdmin(actorId);
        if (!settings.enabled())
            throw new BusinessException(404, "会员功能尚未启用");
        long delta = request.deltaPointUnits();
        if (delta == 0 || (delta > 0) != (request.expiresAt() != null))
            throw new BusinessException(400, "正向积分调整须提供期限，负向不得提供，调整量不能为零",
                    Map.of("reason", "INVALID_ARGUMENT"), null);
        var expiry =
                request.expiresAt() == null ? null : request.expiresAt().withOffsetSameInstant(ZoneOffset.ofHours(8))
                        .toLocalDateTime().truncatedTo(ChronoUnit.MILLIS);
        var account = accounts.initializeAndLock(userId);
        String key = "ADJUST_POINTS:" + actorId + ":" + userId + ":" + requestKey;
        var previous = mapper.pointEntry(key);
        if (previous != null) {
            boolean same = previous.getAvailableDelta() - previous.getDebtDelta() == delta
                    && previous.getReason().equals(request.reason()) &&
                    previous.getEvidenceReference().equals(request.evidenceRef());
            if (delta > 0)
                same = same && mapper.lot(key).getExpiresAt().equals(expiry);
            if (!same)
                throw new BusinessException(409, "幂等键对应的积分调整内容不同",
                        Map.of("reason", "IDEMPOTENCY_CONFLICT"), null);
            return new PointAdjustmentResult(previous.getId(), userId, previous.getAvailableAfter(),
                    previous.getDebtAfter());
        }
        var now = LocalDateTime.now(businessClock);
        if (delta > 0 && !expiry.isAfter(now))
            throw new BusinessException(400, "积分调整期限必须在未来", Map.of("reason", "INVALID_ARGUMENT"), null);
        // 先把过期批次落账再校验可扣余额，避免人工操作动用失效积分。
        for (var lot : mapper.expiredLots(userId, now))
            points.expire(account, lot, null, now);
        if (delta < 0 && delta < -account.getAvailablePointUnits())
            throw new BusinessException(409, "可用积分不足", Map.of("reason", "POINTS_NOT_ENOUGH"), null);
        var entry = new MemberPointEntry();
        entry.setUserId(userId);
        entry.setEventKind("ADJUST");
        entry.setOperationKey(key);
        entry.setActorUserId(actorId);
        entry.setReason(request.reason());
        entry.setEvidenceReference(request.evidenceRef());
        if (delta > 0) {
            // 人工补积分也先抵欠额，不能绕过退款后的欠额约束。
            long offset = Math.min(delta, account.getPointDebtUnits());
            var lot = new MemberPointLot();
            lot.setUserId(userId);
            lot.setSourceKey(key);
            lot.setIssuedUnits(delta);
            lot.setAvailableUnits(delta - offset);
            lot.setDebtOffsetUnits(offset);
            lot.setEarnedAt(now);
            lot.setExpiresAt(expiry);
            lot.setStatus(delta == offset ? "EXHAUSTED" : "ACTIVE");
            mapper.insertLot(lot);
            entry.setAvailableDelta(delta - offset);
            entry.setDebtDelta(-offset);
            points.record(account, entry);
            if (delta > offset)
                mapper.allocate(entry.getId(), lot.getId(), "ADJUST", delta - offset);
            if (offset > 0)
                points.allocateDebtOffset(userId, entry.getId(), lot.getId(), offset,
                        Math.addExact(account.getPointDebtUnits(), offset));
        } else {
            entry.setAvailableDelta(delta);
            points.record(account, entry);
            long remaining = -delta;
            for (var lot : mapper.availableLots(userId, now)) {
                long units = Math.min(remaining, lot.getAvailableUnits());
                lot.setAvailableUnits(lot.getAvailableUnits() - units);
                lot.setReversedUnits(lot.getReversedUnits() + units);
                if (lot.getAvailableUnits() == 0 && lot.getHeldUnits() == 0)
                    lot.setStatus("EXHAUSTED");
                mapper.updateLot(lot);
                mapper.allocate(entry.getId(), lot.getId(), "ADJUST", units);
                remaining -= units;
                if (remaining == 0)
                    break;
            }
            if (remaining != 0)
                throw new IllegalStateException("账户可用积分与来源批次不一致");
        }
        String summary = objectMapper.writeValueAsString(
                Map.of("entryId", entry.getId(), "ledger", "member_point_entries",
                        "reasonField", "reason", "evidenceField", "evidence_reference", "deltaPointUnits", delta,
                        "availableAfter", entry.getAvailableAfter(), "debtAfter", entry.getDebtAfter()));
        mapper.auditAdjustment(actorId, userId, "MEMBER_POINTS_ADJUSTED", summary, now);
        return new PointAdjustmentResult(entry.getId(), userId, account.getAvailablePointUnits(),
                account.getPointDebtUnits());
    }
}
