package com.shiwushop.catalog.controller.admin;

import com.shiwushop.common.BusinessException;
import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.account.entity.user.UserAccount;
import com.shiwushop.account.entity.status.AccountRole;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AdminAuth {

    private final AccountAccess accountAccess;

    /** 从 Session 取用户并再次确认账号启用且具有管理员角色，返回通过校验的用户 ID。 */
    Long requireAdmin(HttpServletRequest request) {
        Long userId = SessionUser.requireId(request);

        UserAccount account = accountAccess.requireActive(userId);
        if (account.getRole() != AccountRole.ADMIN) {
            throw new BusinessException(403, "需要管理员权限");
        }
        return userId;
    }
}
