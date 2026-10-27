package com.shiwushop.common.auth;

import com.shiwushop.common.BusinessException;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

public final class SessionUser {
    private SessionUser() { }

    public static Long currentId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object id = session == null ? null : session.getAttribute("userId");
        return id instanceof Long value ? value : null;
    }

    public static Long requireId(HttpServletRequest request) {
        return requireId(currentId(request));
    }

    public static Long requireId(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        return userId;
    }
}
