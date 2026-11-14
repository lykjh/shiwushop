package com.shiwushop.common.auth;

import com.shiwushop.common.BusinessException;

import jakarta.servlet.http.HttpServletRequest;

public final class SessionUser {
    private SessionUser() {
    }

    public record Identity(Long userId, int authVersion, String sessionRef) {}

    public static void identify(HttpServletRequest request, Identity identity) {
        request.setAttribute(Identity.class.getName(), identity);
    }

    public static Identity current(HttpServletRequest request) {
        return (Identity) request.getAttribute(Identity.class.getName());
    }

    public static Identity require(HttpServletRequest request) {
        Identity identity = current(request);
        if (identity == null)
            throw new BusinessException(401, "请先登录");
        return identity;
    }

    /** 读取过滤器已验证的身份；匿名请求返回 null。 */
    public static Long currentId(HttpServletRequest request) {
        Identity identity = current(request);
        return identity == null ? null : identity.userId();
    }

    /** 从请求取得必需的登录用户 ID，否则返回 401。 */
    public static Long requireId(HttpServletRequest request) {
        return require(request).userId();
    }

    /** 校验已取出的用户 ID，供 Service 和 Controller 共用。 */
    public static Long requireId(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        return userId;
    }
}
