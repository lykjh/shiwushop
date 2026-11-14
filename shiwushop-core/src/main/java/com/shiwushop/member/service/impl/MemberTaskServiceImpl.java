package com.shiwushop.member.service.impl;

import com.shiwushop.member.dto.RewardTaskCandidate;
import com.shiwushop.member.mapper.MemberMapper;
import com.shiwushop.member.service.MemberTaskService;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.member.MemberSettings;
import com.shiwushop.member.vo.AdminRewardTaskPage;
import com.shiwushop.member.vo.AdminRewardTaskVO;
import com.shiwushop.member.vo.MemberRewardTaskVO;
import com.shiwushop.member.vo.TaskRetryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** 认领短事务与订单处理事务分离，进程退出后租约可接管。 */
@Service
@RequiredArgsConstructor
@Transactional
public class MemberTaskServiceImpl implements MemberTaskService {
    private final MemberMapper mapper;
    private final Clock businessClock;
    private final AccountSecurity security;
    private final MemberSettings settings;

    @Override
    @Transactional(readOnly = true)
    public AdminRewardTaskPage adminTasks(Long actorId, Long userId, Long afterId, int size, String kind, String status,
                                          Long orderId) {
        security.requireAdmin(actorId);
        requireEnabled();
        var rows = mapper.adminRewardTasks(userId, afterId, kind, status, orderId, size + 1);
        boolean hasMore = rows.size() > size;
        var page = hasMore ? rows.subList(0, size) : rows;
        var items = page.stream().map(task -> new AdminRewardTaskVO(new MemberRewardTaskVO(task.id(), task.orderId(),
                task.orderItemId(), task.rewardKind(), task.status(),
                task.eligibleAt() == null ? null : task.eligibleAt().atOffset(ZoneOffset.ofHours(8)),
                task.creditedGrowthAmount(), task.creditedPointUnits(), task.exclusionReason()), task.userId(),
                task.ruleVersion(),
                task.nextAttemptAt().atOffset(ZoneOffset.ofHours(8)), task.lastErrorCode())).toList();
        return new AdminRewardTaskPage(items, hasMore, hasMore ? items.getLast().task().id() : null);
    }

    @Override
    public TaskRetryResult retry(Long actorId, Long taskId, String reason) {
        security.requireAdmin(actorId);
        requireEnabled();
        var task = mapper.lockTask(taskId);
        if (task == null)
            throw new BusinessException(404, "权益任务不存在");
        if ("EXCLUDED".equals(task.status()))
            throw new BusinessException(409, "已排除的权益任务不可重试", Map.of("reason", "INVALID_STATE"), null);
        var now = LocalDateTime.now(businessClock);
        String state = task.status();
        var next = task.nextAttemptAt();
        boolean running = task.claimUntil() != null && task.claimUntil().isAfter(now);
        boolean waitingForEligibility = task.eligibleAt() != null && task.eligibleAt().isAfter(now);
        // 只重新排队；已有到期待办、有效租约和未来资格均不被重复请求提前或覆盖。
        if (!"APPLIED".equals(state) && !running
                && ("ATTENTION".equals(state) || (!waitingForEligibility && next.isAfter(now)))) {
            state = "ATTENTION".equals(state) ? "READY" : state;
            next = waitingForEligibility ? task.eligibleAt() : now;
            mapper.retryTask(taskId, state, next);
            mapper.auditTaskRetry(actorId, taskId, task.status(), reason, now);
        }
        return new TaskRetryResult(taskId, state, next.atOffset(ZoneOffset.ofHours(8)));
    }

    private void requireEnabled() {
        if (!settings.enabled())
            throw new BusinessException(404, "会员功能尚未启用");
    }

    public List<RewardTaskCandidate> pending() {
        var now = LocalDateTime.now(businessClock);
        return mapper.pending(now, now.minusDays(7));
    }

    /** 令牌区分本次认领与过期租约，后续处理必须核对同一令牌。 */
    public boolean claim(Long id, String token) {
        var now = LocalDateTime.now(businessClock);
        return mapper.claim(id, token, now, now.plusSeconds(30)) == 1;
    }

    public boolean ownsClaim(Long id, String token) {
        return mapper.ownsClaim(id, token, LocalDateTime.now(businessClock)) == 1;
    }

    /** 结果写回受认领令牌约束，避免旧工作者覆盖接管后的任务。 */
    public void release(Long id, String token, String errorCode) {
        mapper.release(id, token, LocalDateTime.now(businessClock).plusMinutes(1), errorCode);
    }
}
