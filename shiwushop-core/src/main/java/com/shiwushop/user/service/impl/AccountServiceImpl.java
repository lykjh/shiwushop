package com.shiwushop.user.service.impl;

import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.user.auth.AccountAuthCache;
import com.shiwushop.user.entity.status.AccountStatus;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.user.mapper.user.UserAccountMapper;
import com.shiwushop.user.mapper.user.PhoneBindingMapper;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.user.service.AccountService;
import com.shiwushop.user.vo.user.UserInfoVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {
    private final UserAccountMapper userAccountMapper;
    private final PhoneBindingMapper phoneBindings;
    private final PrivacyCipher privacy;
    private final AccountAccess accountAccess;
    private final AccountAuthCache authCache;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 当前账号必须仍是启用状态，才返回基本资料与角色。 */
    @Override
    public UserInfoVO currentUser(Long userId) {
        UserAccount account = accountAccess.requireActive(userId);
        PhoneBindingMapper.PhonePrivate bound = phoneBindings.findPhone(userId);
        String masked = null;
        if (bound != null && bound.getCiphertext() != null) {
            String phone = privacy.decrypt(userId, "login-phone", bound.getKeyVersion(), bound.getCiphertext());
            masked = phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
        }
        return new UserInfoVO(account.getUserId(), account.getUsername(), account.getRole().name(), masked);
    }

    /**
     * 锁定账号行后校验旧密码，更新密码哈希与鉴权版本。
     * 鉴权版本变化使旧会话失效；事务提交前阻止缓存继续提供旧版本状态。
     */
    @Override
    @Transactional(timeout = 30)
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        UserAccount account = userAccountMapper.lockById(userId);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE)
            throw new BusinessException(401, "请重新登录");
        if (!encoder.matches(oldPassword, account.getPasswordHash()))
            throw new BusinessException(400, "原密码错误");
        if (newPassword.equals(oldPassword))
            throw new BusinessException(400, "新密码无效或与原密码相同");
        String hash = encoder.encode(newPassword);
        authCache.blockUntilTransactionEnds(userId,
                new AccountAuthCache.State(AccountStatus.ACTIVE, account.getAuthVersion() + 1));
        if (userAccountMapper.changePassword(userId, account.getAuthVersion(), hash) != 1)
            throw new BusinessException(409, "账号已变更，请重新登录");
        BusinessEventLog.committed("user", "password_changed", "user", userId);
    }
}
