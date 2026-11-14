package com.shiwushop.user.service;

public interface PhoneBindingService {
    /** 绑定码仅供已登录的有效账号申请。 */
    long sendCode(Long userId, String phone, String remoteAddress);

    void bind(Long userId, String currentPassword, String phone, String code);
}
