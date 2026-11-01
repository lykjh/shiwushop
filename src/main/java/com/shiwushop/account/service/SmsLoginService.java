package com.shiwushop.account.service;

import com.shiwushop.account.vo.user.LoginVO;
import jakarta.servlet.http.HttpServletRequest;

public interface SmsLoginService {
    void sendCode(String phone, String remoteAddress);
    LoginVO login(String phone, String code, HttpServletRequest request);
}
