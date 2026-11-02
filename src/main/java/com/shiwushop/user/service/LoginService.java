package com.shiwushop.user.service;

import com.shiwushop.user.dto.user.LoginDTO;
import com.shiwushop.user.vo.user.LoginVO;
import jakarta.servlet.http.HttpServletRequest;

/** 用户名密码登录。 */
public interface LoginService {
    /** 验证密码与风险状态，成功后轮换并建立登录 Session。 */
    LoginVO login(LoginDTO dto, HttpServletRequest request);
}
