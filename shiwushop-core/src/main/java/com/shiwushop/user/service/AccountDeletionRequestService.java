package com.shiwushop.user.service;

import com.shiwushop.user.entity.user.AccountDeletionRequest;

/** 查询注销申请，并在核对当前会话版本后提交或撤回。 */
public interface AccountDeletionRequestService {
    AccountDeletionRequest request(Long userId, int sessionVersion);

    AccountDeletionRequest latest(Long userId);

    AccountDeletionRequest withdraw(Long userId, int sessionVersion);
}
