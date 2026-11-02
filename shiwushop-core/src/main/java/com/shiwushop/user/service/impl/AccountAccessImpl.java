package com.shiwushop.user.service.impl;

import com.shiwushop.user.auth.AccountAuthCache;

import com.shiwushop.common.BusinessException;


import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.user.entity.status.AccountRole;
import com.shiwushop.user.entity.status.AccountStatus;
import com.shiwushop.user.mapper.user.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import com.shiwushop.user.service.AccountAccess;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccountAccessImpl implements AccountAccess {
    private final UserAccountMapper userAccountMapper;
    private final AccountAuthCache authCache;

    /** 从鉴权状态缓存确认账号仍启用；缓存缺失或账号停用时要求重新登录。 */
    public void checkActive(Long userId) {

        AccountAuthCache.State state = authCache.current(userId);
        if (state == null || state.status() != AccountStatus.ACTIVE) {
            throw new BusinessException(401, "请重新登录");
        }
    }

    /** 从数据库读取启用中的完整账号，供需要角色或鉴权版本的业务使用。 */
    public UserAccount requireActive(Long userId) {

        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(401, "请重新登录");
        }
        return account;
    }

    /** 确认当前用户是启用中的商家；否则拒绝商家专属操作。 */
    public void requireMerchant(Long userId) {

        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE
                || account.getRole() != AccountRole.MERCHANT) {
            throw new BusinessException(403, "需要商家权限");
        }
    }
    /** 管理权限使用用户模块的账号状态，公共设施不读取用户表。 */
    @Override
    public void requireAdmin(Long userId) {
        if (requireActive(userId).getRole() != AccountRole.ADMIN)
            throw new BusinessException(403, "需要管理员权限");
    }

    /** 开店和转店共用经营者资格检查；保持与店铺操作相同的事务。 */
    @Override
    public UserAccount requireShopOwner(Long userId, String missingMessage) {
        UserAccount owner = userAccountMapper.selectById(userId);
        if (owner == null) throw new BusinessException(404, missingMessage);
        if (owner.getStatus() != AccountStatus.ACTIVE
                || (owner.getRole() != AccountRole.USER && owner.getRole() != AccountRole.MERCHANT))
            throw new BusinessException(409, "该账号不能经营店铺");
        return owner;
    }

    /** 店铺写入成功后提升普通用户；用户角色的写入只留在用户模块。 */
    @Override
    public void promoteMerchant(Long userId) {
        userAccountMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                .eq(UserAccount::getUserId, userId).eq(UserAccount::getRole, AccountRole.USER)
                .set(UserAccount::getRole, AccountRole.MERCHANT));
    }
}
