package com.shiwushop.user.service;

import com.shiwushop.common.auth.SessionUser.Identity;
import com.shiwushop.user.dto.user.AccountProofDTO;

import java.time.Instant;
import java.util.List;

/** 查询当前账号的登录设备并核验下线凭证。 */
public interface UserSessionService {
    record SessionView(String sessionRef, Instant createdAt, Instant lastAccessedAt,
                       Instant expiresAt, boolean current) {
    }

    List<SessionView> list(Identity current);

    long sendCode(Long userId, String remoteAddress);

    void revoke(Identity current, String ref, AccountProofDTO verification);

    void revokeOthers(Identity current, AccountProofDTO verification);
}
