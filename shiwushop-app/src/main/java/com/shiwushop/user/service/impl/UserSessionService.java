package com.shiwushop.user.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.user.dto.user.AccountProofDTO;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserSessionService {
    /** 撤销登录会话时二次验证凭据的用途标识。 */
    private static final String PURPOSE = "session-revoke";
    private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> repositories;
    private final AccountProofService proof;

    public record SessionView(
        /** 供本人会话管理使用的会话引用。 */
        String sessionRef,
        /** 登录会话创建时间。 */
        Instant createdAt,
        /** 会话最近访问时间。 */
        Instant lastAccessedAt,
        /** 依据最近访问时间和闲置期限计算的会话过期时间。 */
        Instant expiresAt,
        /** 是否为当前请求所使用的登录会话。 */
        boolean current
    ) {
    }

    public List<SessionView> list(Long userId, HttpSession current) {
        return own(userId).values().stream()
                .filter(session -> !session.isExpired() && session.getAttribute("sessionRef") instanceof String)
                .map(session -> new SessionView(session.getAttribute("sessionRef"), session.getCreationTime(),
                        session.getLastAccessedTime(), session.getLastAccessedTime().plus(session.getMaxInactiveInterval()),
                        session.getId().equals(current.getId())))
                .sorted(Comparator.comparing(SessionView::createdAt).reversed()).toList();
    }

    public void sendCode(Long userId, String remoteAddress) {
        proof.sendCode(userId, PURPOSE, remoteAddress);
    }

    public void revoke(Long userId, HttpSession current, String ref, AccountProofDTO verification) {
        proof.verify(userId, PURPOSE, verification.currentPassword(), verification.smsVerification());
        for (Session session : own(userId).values()) {
            if (ref.equals(session.getAttribute("sessionRef")) && !session.isExpired()) {
                if (session.getId().equals(current.getId()))
                    throw new BusinessException(409, "当前会话请使用退出登录");
                repository().deleteById(session.getId());
                return;
            }
        }
        throw new BusinessException(404, "会话不存在");
    }

    public void revokeOthers(Long userId, HttpSession current, AccountProofDTO verification) {
        proof.verify(userId, PURPOSE, verification.currentPassword(), verification.smsVerification());
        for (Session session : own(userId).values()) {
            if (!session.getId().equals(current.getId()))
                repository().deleteById(session.getId());
        }
    }

    private Map<String, ? extends Session> own(Long userId) {
        return repository().findByPrincipalName(userId.toString());
    }

    private FindByIndexNameSessionRepository<? extends Session> repository() {
        var repository = repositories.getIfAvailable();
        if (repository == null)
            throw new BusinessException(503, "会话服务暂不可用");
        return repository;
    }
}
