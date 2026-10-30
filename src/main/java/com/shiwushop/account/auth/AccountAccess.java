package com.shiwushop.account.auth;

import com.shiwushop.common.auth.SessionUser;

import com.shiwushop.common.BusinessException;


import com.shiwushop.account.entity.user.UserAccount;
import com.shiwushop.account.entity.status.AccountRole;
import com.shiwushop.account.entity.status.AccountStatus;
import com.shiwushop.account.mapper.user.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccountAccess {
    private final UserAccountMapper userAccountMapper;
    private final AccountAuthCache authCache;

    /** 从鉴权状态缓存确认账号仍启用；缓存缺失或账号停用时要求重新登录。 */
    public void checkActive(Long userId) {
        SessionUser.requireId(userId);
        AccountAuthCache.State state = authCache.current(userId);
        if (state == null || state.status() != AccountStatus.ACTIVE) {
            throw new BusinessException(401, "请重新登录");
        }
    }

    /** 从数据库读取启用中的完整账号，供需要角色或鉴权版本的业务使用。 */
    public UserAccount requireActive(Long userId) {
        SessionUser.requireId(userId);
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(401, "请重新登录");
        }
        return account;
    }

    /** 确认当前用户是启用中的商家；否则拒绝商家专属操作。 */
    public void requireMerchant(Long userId) {
        SessionUser.requireId(userId);
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE
                || account.getRole() != AccountRole.MERCHANT) {
            throw new BusinessException(403, "需要商家权限");
        }
    }
}
