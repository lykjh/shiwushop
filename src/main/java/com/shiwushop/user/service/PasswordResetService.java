package com.shiwushop.user.service;

import com.shiwushop.user.vo.user.PasswordResetVerifyVO;
import jakarta.servlet.http.HttpServletRequest;

/** 已验证手机号的密码找回；与首次注册、旧密码改密保持独立用途。 */
public interface PasswordResetService {
    /** 仅向已绑定且启用的账号发送找回码；未绑定号码对外仍返回成功。 */
    void sendCode(String phone, HttpServletRequest request);

    /** 消费找回码并签发绑定当前 Session 的五分钟重设票据。 */
    PasswordResetVerifyVO verify(String phone, String code, HttpServletRequest request);

    /** 在事务中重设票据指定账号的密码、提升鉴权版本，撤销全部旧登录态。 */
    void reset(String ticket, String newPassword, HttpServletRequest request);
}
