package com.shiwushop.account.service;

import com.shiwushop.account.vo.user.LoginVO;
import com.shiwushop.account.vo.user.SmsEntryVerifyVO;
import jakarta.servlet.http.HttpServletRequest;

public interface SmsEntryService {
    void sendCode(String phone, HttpServletRequest request);
    SmsEntryVerifyVO verify(String phone, String code, HttpServletRequest request);
    LoginVO register(String phone, String ticket, String password, HttpServletRequest request);
}
