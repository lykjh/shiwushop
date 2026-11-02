package com.shiwushop.common.auth;

import com.shiwushop.common.BusinessException;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

public final class SessionUser {
    private SessionUser() { }

    /** 从现有 Session 读取用户 ID；匿名请求返回 null 且不创建 Session。 */
    public static Long currentId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object id = session == null ? null : session.getAttribute("userId");
        return id instanceof Long value ? value : null;
    }

    /** 从请求 Session 取得必需的登录用户 ID，否则返回 401。 */
    public static Long requireId(HttpServletRequest request) {
        return requireId(currentId(request));
    }

    /** 校验已取出的用户 ID，供 Service 和 Controller 共用。 */
    public static Long requireId(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        return userId;
    }
}
