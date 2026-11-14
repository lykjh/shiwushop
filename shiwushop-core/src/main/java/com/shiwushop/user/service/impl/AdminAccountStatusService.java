package com.shiwushop.user.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.user.auth.AccountAuthCache;
import com.shiwushop.user.entity.status.AccountRole;
import com.shiwushop.user.entity.status.AccountStatus;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.user.mapper.user.AdminAccountActionMapper;
import com.shiwushop.user.mapper.user.UserAccountMapper;
import com.shiwushop.user.vo.user.AdminAccountVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminAccountStatusService {
    private final UserAccountMapper accounts;
    private final AdminAccountActionMapper actions;
    private final AccountAuthCache authCache;

    @Transactional(timeout = 30)
    public AdminAccountVO change(Long actorId, Long targetId, AccountStatus next, String reason) {
        UserAccount actor = accounts.lockById(actorId);
        if (actor == null || actor.getStatus() != AccountStatus.ACTIVE || actor.getRole() != AccountRole.ADMIN)
            throw new BusinessException(403, "需要管理员权限");
        UserAccount target = actorId.equals(targetId) ? actor : accounts.lockById(targetId);
        if (target == null)
            throw new BusinessException(404, "账号不存在");
        if (actorId.equals(targetId) || target.getRole() == AccountRole.ADMIN)
            throw new BusinessException(403, "不能修改管理员账号状态");
        if (target.getStatus() == next)
            return new AdminAccountVO(targetId, target.getRole(), next);

        authCache.blockUntilTransactionEnds(targetId,
                new AccountAuthCache.State(next, target.getAuthVersion() + 1));
        if (accounts.changeStatus(targetId, target.getAuthVersion(), target.getStatus().name(), next.name()) != 1)
            throw new BusinessException(409, "账号已变更，请重试");
        LocalDateTime now = LocalDateTime.now();
        String action = next == AccountStatus.DISABLED ? "ACCOUNT_DISABLED" : "ACCOUNT_RESTORED";
        actions.audit(actorId, action, targetId, target.getStatus() + "->" + next + ": " + reason, now);
        if (next == AccountStatus.DISABLED)
            actions.actionItem(actions.lastAuditId(), actorId, targetId, reason, now);
        else
            actions.resolveOpen(targetId);
        BusinessEventLog.committed("user", action.toLowerCase(), "user", targetId);
        return new AdminAccountVO(targetId, target.getRole(), next);
    }
}
