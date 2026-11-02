package com.shiwushop.user.service.impl;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** 为首次使用手机号的用户生成不含手机号信息的登录名。 */
@Component
class RandomUsername {
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private final SecureRandom random = new SecureRandom();

    /** 使用安全随机源生成固定长度账号，唯一性由数据库索引最终保证。 */
    String next() {
        StringBuilder name = new StringBuilder("sw");
        for (int i = 0; i < 20; i++) name.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        return name.toString();
    }
}
