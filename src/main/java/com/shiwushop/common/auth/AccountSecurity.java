package com.shiwushop.common.auth;

/** 公共设施只请求权限判断；账号表和状态缓存由用户模块维护。 */
public interface AccountSecurity {
    void checkActive(Long userId);
    void requireMerchant(Long userId);
    void requireAdmin(Long userId);
}
