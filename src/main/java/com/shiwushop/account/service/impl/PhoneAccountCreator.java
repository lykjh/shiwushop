package com.shiwushop.account.service.impl;

import com.shiwushop.account.entity.status.AccountRole;
import com.shiwushop.account.entity.status.AccountStatus;
import com.shiwushop.account.entity.user.UserAccount;
import com.shiwushop.account.entity.user.UserProfile;
import com.shiwushop.account.mapper.user.PhoneBindingMapper;
import com.shiwushop.account.mapper.user.UserAccountMapper;
import com.shiwushop.account.mapper.user.UserProfileMapper;
import com.shiwushop.account.privacy.PrivacyCipher;
import com.shiwushop.account.vo.user.LoginVO;
import com.shiwushop.common.logging.BusinessEventLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 一个数据库事务创建用户、账号和已验证手机号绑定。 */
@Service
@RequiredArgsConstructor
public class PhoneAccountCreator {
    private final UserProfileMapper profiles;
    private final UserAccountMapper accounts;
    private final PhoneBindingMapper bindings;
    private final PrivacyCipher privacy;

    /** 任一步写入失败即回滚三张表，调用方在提交完成后再建立 Session。 */
    @Transactional(timeout = 30)
    public LoginVO create(String username, String passwordHash, String phone, String phoneDigest) {
        UserProfile profile = new UserProfile();
        profiles.insertEmpty(profile);
        UserAccount account = new UserAccount();
        account.setUserId(profile.getId());
        account.setUsername(username);
        account.setPasswordHash(passwordHash);
        account.setRole(AccountRole.USER);
        account.setStatus(AccountStatus.ACTIVE);
        accounts.insert(account);
        bindings.insert(profile.getId(), privacy.encrypt(profile.getId(), "login-phone", phone), phoneDigest);
        BusinessEventLog.committed("account", "sms_entry_register", "user", profile.getId());
        return new LoginVO(profile.getId(), username);
    }
}
