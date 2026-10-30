package com.shiwushop.account.service;

import com.shiwushop.account.dto.user.RegisterDTO;
import jakarta.servlet.http.HttpServletRequest;

/** 会员注册与账号创建。 */
public interface RegisterService {
    /** 验证注册入口和滑块凭证，在事务内创建资料与账号。 */
    void register(RegisterDTO dto, HttpServletRequest request);
}
