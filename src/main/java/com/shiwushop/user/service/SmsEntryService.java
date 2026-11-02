package com.shiwushop.user.service;

import com.shiwushop.user.vo.user.LoginVO;
import com.shiwushop.user.vo.user.SmsEntryVerifyVO;
import jakarta.servlet.http.HttpServletRequest;

public interface SmsEntryService {
    void sendCode(String phone, HttpServletRequest request);
    SmsEntryVerifyVO verify(String phone, String code, HttpServletRequest request);
    LoginVO register(String phone, String ticket, String password, HttpServletRequest request);
}
