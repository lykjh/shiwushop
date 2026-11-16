package com.shiwushop.user.entity.user;

import java.time.LocalDateTime;

/**
 * @param id 账号注销申请 ID
 * @param userId 用户 ID
 * @param status 账号注销申请当前的状态
 * @param requestedAt 申请发起的时间
 * @param deadlineAt 申请处理期限
 * @param withdrawnAt 用户主动撤回申请的时间
 */
public record AccountDeletionRequest(Long id, Long userId, String status, LocalDateTime requestedAt,
                                     LocalDateTime deadlineAt, LocalDateTime withdrawnAt) {
}
