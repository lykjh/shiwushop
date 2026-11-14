package com.shiwushop.user.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.user.mapper.user.UserAccountMapper;
import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.user.vo.user.AdminAccountVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminAccountQueryService {
    private final AccountAccess accountAccess;
    private final UserAccountMapper accounts;

    /** 管理员只能查询账号身份与状态，不返回密码和私人资料。 */
    public AdminAccountVO detail(Long adminId, Long userId) {
        accountAccess.requireAdmin(adminId);
        UserAccount account = accounts.selectById(userId);
        if (account == null)
            throw new BusinessException(404, "账号不存在");
        return new AdminAccountVO(account.getUserId(), account.getRole(), account.getStatus());
    }
}
