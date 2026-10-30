package com.shiwushop.account.privacy;

import com.shiwushop.common.BusinessException;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class PrivacyCipher {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();
    private final String keyBase64;

    /** 保存配置中的 Base64 密钥；实际加解密时再验证密钥是否存在及长度。 */
    public PrivacyCipher(@Value("${shop.privacy.key-v1-base64:}") String keyBase64) {
        this.keyBase64 = keyBase64;
    }

    /**
     * 使用随机 IV 和 AES-GCM 加密地址字段；AAD 绑定用户与字段名，防止密文跨用户或字段搬用。
     * 返回值按 IV、密文和认证标签的顺序存储，供 {@link #decrypt} 解码。
     */
    public byte[] encrypt(Long userId, String field, String value) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(userId, field));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, "收货信息加密失败", e);
        }
    }

    /**
     * 按记录的密钥版本解密地址字段，并验证用户、字段名和 GCM 认证标签。
     * 版本或密文格式错误时拒绝返回数据，避免展示错误的收货信息。
     */
    public String decrypt(Long userId, String field, int version, byte[] value) {
        if (version != 1 || value == null || value.length <= IV_BYTES) {
            throw new BusinessException(500, "收货信息解密失败");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(TAG_BITS, value, 0, IV_BYTES));
            cipher.updateAAD(aad(userId, field));
            return new String(cipher.doFinal(value, IV_BYTES, value.length - IV_BYTES),
                    StandardCharsets.UTF_8);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, "收货信息解密失败", e);
        }
    }

    /** 将配置值解码为 32 字节 AES 密钥；缺失或格式错误时报告配置故障。 */
    private SecretKeySpec key() {
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new BusinessException(503, "收货信息加密密钥未配置");
        }
        try {
            byte[] key = Base64.getDecoder().decode(keyBase64);
            if (key.length != 32) throw new IllegalArgumentException("key length");
            return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException e) {
            throw new BusinessException(503, "收货信息加密密钥配置错误");
        }
    }

    /** 生成与收货地址所属用户和字段关联的 GCM 附加认证数据。 */
    private byte[] aad(Long userId, String field) {
        return ("addresses:" + userId + ":" + field).getBytes(StandardCharsets.UTF_8);
    }
}
