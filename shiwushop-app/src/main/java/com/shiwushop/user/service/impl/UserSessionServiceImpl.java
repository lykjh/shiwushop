package com.shiwushop.user.service.impl;

import com.shiwushop.user.service.UserSessionService;
import com.shiwushop.user.service.AccountProofService;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.SessionUser.Identity;
import com.shiwushop.user.auth.LoginSessions;
import com.shiwushop.user.dto.user.AccountProofDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserSessionServiceImpl implements UserSessionService {
    private static final String PURPOSE = "session-revoke";
    private final LoginSessions sessions;
    private final AccountProofService proof;

    @Override
    public List<SessionView> list(Identity current) {
        return sessions.list(current).stream()
                .map(entry -> new SessionView(entry.sessionRef(), entry.createdAt(), entry.lastAccessedAt(),
                        entry.expiresAt(), entry.sessionRef().equals(current.sessionRef())))
                .sorted(Comparator.comparing(SessionView::createdAt).reversed()).toList();
    }

    @Override
    public long sendCode(Long userId, String remoteAddress) {
        return proof.sendCode(userId, PURPOSE, remoteAddress);
    }

    @Override
    public void revoke(Identity current, String ref, AccountProofDTO verification) {
        proof.verify(current.userId(), PURPOSE, verification.currentPassword(), verification.smsVerification());
        if (current.sessionRef().equals(ref))
            throw new BusinessException(409, "当前会话请使用退出登录");
        sessions.revoke(current, ref);
    }

    @Override
    public void revokeOthers(Identity current, AccountProofDTO verification) {
        proof.verify(current.userId(), PURPOSE, verification.currentPassword(), verification.smsVerification());
        sessions.revoke(current, "");
    }
}
