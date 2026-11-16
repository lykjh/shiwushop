package com.shiwushop.user.service;

import com.shiwushop.user.entity.status.AccountStatus;
import com.shiwushop.user.vo.user.AdminAccountVO;

/** 处理管理员发起的账号状态变更。 */
public interface AdminAccountStatusService {
    AdminAccountVO change(Long actorId, Long targetId, AccountStatus next, String reason);
}
