package com.shiwushop.account.service;

import com.shiwushop.account.dto.user.LoginDTO;
import com.shiwushop.account.vo.user.LoginVO;
import jakarta.servlet.http.HttpServletRequest;

/** 用户名密码登录。 */
public interface LoginService {
    /** 验证密码与风险状态，成功后轮换并建立登录 Session。 */
    LoginVO login(LoginDTO dto, HttpServletRequest request);
}
