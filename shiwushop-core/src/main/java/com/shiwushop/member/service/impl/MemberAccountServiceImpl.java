package com.shiwushop.member.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.member.MemberSettings;
import com.shiwushop.member.mapper.MemberMapper;
import com.shiwushop.member.model.*;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.member.vo.MemberOverview;
import com.shiwushop.member.vo.MemberGrowthEntryVO;
import com.shiwushop.member.vo.MemberPointEntryVO;
import com.shiwushop.member.vo.MemberPointLotPage;
import com.shiwushop.member.vo.MemberPointLotVO;
import com.shiwushop.member.vo.MemberRewardTaskPage;
import com.shiwushop.member.vo.MemberRewardTaskVO;
import com.shiwushop.member.vo.AdminGrowthEntryPage;
import com.shiwushop.member.vo.AdminGrowthEntryVO;
import com.shiwushop.member.vo.AdminPointEntryPage;
import com.shiwushop.member.vo.AdminPointEntryVO;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class MemberAccountServiceImpl implements MemberAccountService {
    private final MemberMapper mapper;
    private final MemberSettings settings;
    private final AccountSecurity security;
    private final Clock businessClock;

    @Override
    public List<com.shiwushop.member.vo.MemberLevelVO> levels() {
        requireEnabled();
        return mapper.publicLevels();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public com.shiwushop.member.vo.MemberLevelVO lockLevel(String code) {
        requireEnabled();
        return mapper.lockLevel(code);
    }

    /** 在调用方事务中初始化并锁账户，使余额变动与等级历史串行落账。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MemberAccount initializeAndLock(Long userId) {
        mapper.initialize(userId, settings.rewardStartAt());
        var account = mapper.lockAccount(userId);
        if (account == null)
            throw new BusinessException(404, "会员用户不存在");
        mapper.initializeHistory(userId);
        return account;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MemberOverview overview(Long userId) {
        requireEnabled();
        security.checkActive(userId);
        return overviewOf(requireAccount(userId));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MemberOverview adminOverview(Long actorId, Long userId) {
        security.requireAdmin(actorId);
        requireEnabled();
        return overviewOf(requireAccount(userId));
    }

    private MemberAccount requireAccount(Long userId) {
        var account = mapper.account(userId);
        if (account == null) {
            if (!mapper.userExists(userId))
                throw new BusinessException(404, "会员用户不存在");
            // 查询不隐式建账户，未就绪明确返回 503，避免把缺失资料伪装成零余额。
            log.warn("会员账户待初始化 userId={}", userId);
            throw new BusinessException(503, "会员账户尚未就绪",
                    Map.of("reason", "DEPENDENCY_UNAVAILABLE"), null);
        }
        return account;
    }

    private MemberOverview overviewOf(MemberAccount account) {
        Long userId = account.getUserId();
        // 同一数据库快照扣除尚未由到期任务落账的失效余额，GET 不改变账本。
        long available = account.getAvailablePointUnits() -
                mapper.expiredAvailableUnits(userId, LocalDateTime.now(businessClock));
        BigDecimal next = mapper.levels().stream().map(MembershipLevel::minGrowthAmount)
                .filter(min -> min.compareTo(account.getGrowthAmount()) > 0).min(BigDecimal::compareTo)
                .map(min -> min.subtract(account.getGrowthAmount())).orElse(null);
        return new MemberOverview(account.getLevelCode(), account.getGrowthAmount(),
                account.getGrowthAmount().longValue(),
                next, mapper.pendingGrowth(userId), available, account.getHeldPointUnits(),
                account.getPointDebtUnits());
    }

    @Override
    public List<MemberGrowthEntryVO> growthEntries(Long userId, Long beforeId, int limit) {
        requireEnabled();
        security.checkActive(userId);
        return mapper.growthEntries(userId, beforeId, limit, null).stream().map(MemberGrowthEntryVO::from).toList();
    }

    @Override
    public List<MemberPointEntryVO> pointEntries(Long userId, Long beforeId, int limit) {
        requireEnabled();
        security.checkActive(userId);
        return mapper.pointEntries(userId, beforeId, limit, null).stream().map(MemberPointEntryVO::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AdminGrowthEntryPage adminGrowthEntries(Long actorId, Long userId, Long afterId, int size, Long orderId) {
        security.requireAdmin(actorId);
        requireEnabled();
        requireAccount(userId);
        var rows = mapper.growthEntries(userId, afterId, size + 1, orderId);
        boolean hasMore = rows.size() > size;
        var page = hasMore ? rows.subList(0, size) : rows;
        var items = page.stream().map(entry -> new AdminGrowthEntryVO(MemberGrowthEntryVO.from(entry),
                entry.getSourceEntryId(), entry.getActorUserId(), entry.getEvidenceReference())).toList();
        return new AdminGrowthEntryPage(items, hasMore, hasMore ? items.getLast().entry().id() : null);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminPointEntryPage adminPointEntries(Long actorId, Long userId, Long afterId, int size, Long orderId) {
        security.requireAdmin(actorId);
        requireEnabled();
        requireAccount(userId);
        var rows = mapper.pointEntries(userId, afterId, size + 1, orderId);
        boolean hasMore = rows.size() > size;
        var page = hasMore ? rows.subList(0, size) : rows;
        var items = page.stream().map(entry -> new AdminPointEntryVO(MemberPointEntryVO.from(entry),
                entry.getActorUserId(), entry.getEvidenceReference())).toList();
        return new AdminPointEntryPage(items, hasMore, hasMore ? items.getLast().entry().id() : null);
    }

    @Override
    public String levelAt(Long userId, LocalDateTime at) {
        return mapper.levelAt(userId, at);
    }

    @Override
    public MemberPointLotPage pointLots(Long userId, Long afterId, int size, String status) {
        requireEnabled();
        security.checkActive(userId);
        var now = LocalDateTime.now(businessClock);
        var rows = mapper.pointLots(userId, afterId, status, now, size + 1);
        boolean hasMore = rows.size() > size;
        var page = hasMore ? rows.subList(0, size) : rows;
        var items = page.stream().map(lot -> {
            boolean expired = !now.isBefore(lot.getExpiresAt()) && lot.getAvailableUnits() > 0;
            return new MemberPointLotVO(lot.getId(), lot.getIssuedUnits(), expired ? 0 : lot.getAvailableUnits(),
                    lot.getHeldUnits(), lot.getSpentUnits(),
                    lot.getExpiredUnits() + (expired ? lot.getAvailableUnits() : 0),
                    lot.getReversedUnits(), lot.getDebtOffsetUnits(), lot.getEarnedAt().atOffset(ZoneOffset.ofHours(8)),
                    lot.getExpiresAt().atOffset(ZoneOffset.ofHours(8)),
                    expired && lot.getHeldUnits() == 0 ? "EXPIRED" : lot.getStatus());
        }).toList();
        return new MemberPointLotPage(items, hasMore, hasMore ? items.getLast().id() : null);
    }

    @Override
    public MemberRewardTaskPage rewardTasks(Long userId, Long afterId, int size, String kind, String status,
                                            Long orderId) {
        requireEnabled();
        security.checkActive(userId);
        var rows = mapper.rewardTaskViews(userId, afterId, kind, status, orderId, size + 1);
        boolean hasMore = rows.size() > size;
        var page = hasMore ? rows.subList(0, size) : rows;
        var items = page.stream().map(task -> new MemberRewardTaskVO(task.id(), task.orderId(), task.orderItemId(),
                task.rewardKind(), task.status(),
                task.eligibleAt() == null ? null : task.eligibleAt().atOffset(ZoneOffset.ofHours(8)),
                task.creditedGrowthAmount(), task.creditedPointUnits(), task.exclusionReason())).toList();
        return new MemberRewardTaskPage(items, hasMore, hasMore ? items.getLast().id() : null);
    }

    private void requireEnabled() {
        if (!settings.enabled())
            throw new BusinessException(404, "会员功能尚未启用");
    }
}
