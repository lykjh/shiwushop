package com.shiwushop.common.auth;

import com.shiwushop.common.auth.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminAuth {

    private final AccountSecurity accountAccess;

    /** 从 Session 取用户并再次确认账号启用且具有管理员角色，返回通过校验的用户 ID。 */
    public Long requireAdmin(HttpServletRequest request) {
        Long userId = SessionUser.requireId(request);

        accountAccess.requireAdmin(userId);
        return userId;
    }
}
