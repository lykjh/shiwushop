package com.shiwushop.user.service;

public interface PhoneBindingService {
    void sendCode(Long userId, String phone, String remoteAddress);
    void bind(Long userId, String currentPassword, String phone, String code);
}
