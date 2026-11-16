package com.shiwushop.user.service;

import com.shiwushop.user.vo.user.AdminAccountVO;

/** 查询管理员可查看的账号详情。 */
public interface AdminAccountQueryService {
    AdminAccountVO detail(Long adminId, Long userId);
}
