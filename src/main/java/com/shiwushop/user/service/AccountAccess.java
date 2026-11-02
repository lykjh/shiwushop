package com.shiwushop.user.service;

import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.user.entity.user.UserAccount;

/** 账号状态、角色检查和经营者资格；调用方不接触账号 Mapper。 */
public interface AccountAccess extends AccountSecurity {
    UserAccount requireActive(Long userId);
    UserAccount requireShopOwner(Long userId, String missingMessage);
    void promoteMerchant(Long userId);
}
