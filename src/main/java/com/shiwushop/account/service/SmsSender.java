package com.shiwushop.account.service;

/** 短信渠道边界；测试可替换渠道，不改登录业务。 */
public interface SmsSender {
    boolean configured();
    void send(String phone, String code);
}
