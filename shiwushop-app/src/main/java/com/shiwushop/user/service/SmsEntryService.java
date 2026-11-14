package com.shiwushop.user.service;

import com.shiwushop.user.vo.user.LoginVO;
import com.shiwushop.user.vo.user.SmsEntryVerifyVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 处理手机号验证码登录和首次使用手机号时的账号注册。 */
public interface SmsEntryService {
    /** 新入口对未绑定手机号也实际发送验证码，另设全站短信发送预算。 */
    void sendCode(String phone, HttpServletRequest request);

    /** 验证码仅消费一次；已有账号登录，首次使用则签发与当前 Session 绑定的票据。 */
    SmsEntryVerifyVO verify(String phone, String code, HttpServletRequest request, HttpServletResponse response);

    /** 票据消费后逐次尝试随机账号，唯一索引负责并发冲突的最终裁决。 */
    LoginVO register(String phone, String ticket, String password, HttpServletRequest request, HttpServletResponse response);
}
