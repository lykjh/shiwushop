package com.shiwushop.account.service;

import com.shiwushop.account.vo.user.LoginVO;
import jakarta.servlet.http.HttpServletRequest;

public interface SmsService {
    void sendBindCode(Long userId, String phone, String remoteAddress);
    void bind(Long userId, String currentPassword, String phone, String code);
    void sendLoginCode(String phone, String remoteAddress);
    LoginVO login(String phone, String code, HttpServletRequest request);
}
