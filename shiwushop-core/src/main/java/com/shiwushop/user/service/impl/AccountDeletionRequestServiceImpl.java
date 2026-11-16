package com.shiwushop.user.service.impl;

import com.shiwushop.user.entity.user.AccountDeletionRequest;
import com.shiwushop.user.service.AccountDeletionRequestService;
import com.shiwushop.common.BusinessException;
import com.shiwushop.user.entity.status.AccountStatus;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.user.mapper.user.AccountDeletionRequestMapper;
import com.shiwushop.user.mapper.user.UserAccountMapper;
import com.shiwushop.user.service.AccountTradeGate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AccountDeletionRequestServiceImpl implements AccountDeletionRequestService, AccountTradeGate {
    private final UserAccountMapper accounts;
    private final AccountDeletionRequestMapper requests;
    private final Clock businessClock;

    @Transactional(timeout = 30)
    public AccountDeletionRequest request(Long userId) {
        requireActiveLocked(userId);
        return createAfterLock(userId);
    }

    /** HTTP 申请在账号锁下重核已验证会话版本。 */
    @Override
    @Transactional(timeout = 30)
    public AccountDeletionRequest request(Long userId, int sessionVersion) {
        requireCurrentSession(userId, sessionVersion);
        return createAfterLock(userId);
    }

    private AccountDeletionRequest createAfterLock(Long userId) {
        var pending = requests.pending(userId);
        if (pending != null)
            return pending;
        LocalDateTime now = LocalDateTime.now(businessClock);
        requests.request(userId, now, now.plusDays(7));
        return requests.pending(userId);
    }

    @Override
    public AccountDeletionRequest latest(Long userId) {
        return requests.latest(userId);
    }

    @Override
    @Transactional(timeout = 30)
    public AccountDeletionRequest withdraw(Long userId, int sessionVersion) {
        requireCurrentSession(userId, sessionVersion);
        var pending = requests.pending(userId);
        if (pending == null) {
            var latest = requests.latest(userId);
            if (latest == null)
                throw new BusinessException(404, "没有注销申请");
            return latest;
        }
        LocalDateTime now = LocalDateTime.now(businessClock);
        if (!now.isBefore(pending.deadlineAt()))
            throw new BusinessException(409, "撤回期限已过，等待人工复核");
        if (requests.withdraw(pending.id(), now) != 1)
            throw new BusinessException(409, "申请状态已变更，请重试");
        return requests.latest(userId);
    }

    @Override
    public void requireCanStartTrade(Long userId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("新增交易门禁必须与交易创建同一事务");
        requireActiveLocked(userId);
        if (requests.pending(userId) != null)
            throw new BusinessException(409, "注销申请处理中，暂不能新增交易",
                    Map.of("reason", "ACCOUNT_DELETION_PENDING"), null);
    }

    private UserAccount requireActiveLocked(Long userId) {
        UserAccount account = accounts.lockById(userId);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE)
            throw new BusinessException(401, "请重新登录");
        return account;
    }

    private void requireCurrentSession(Long userId, int sessionVersion) {
        if (requireActiveLocked(userId).getAuthVersion() != sessionVersion)
            throw new BusinessException(401, "请重新登录");
    }
}
