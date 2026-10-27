package com.shiwushop.controller.admin;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountAccess;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.entity.user.UserAccount;
import com.shiwushop.entity.status.AccountRole;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AdminAuth {

    private final AccountAccess accountAccess;

    Long requireAdmin(HttpServletRequest request) {
        Long userId = SessionUser.requireId(request);

        UserAccount account = accountAccess.requireActive(userId);
        if (account.getRole() != AccountRole.ADMIN) {
            throw new BusinessException(403, "需要管理员权限");
        }
        return userId;
    }
}
