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

    public PrivacyCipher(@Value("${shop.privacy.key-v1-base64:}") String keyBase64) {
        this.keyBase64 = keyBase64;
    }

    /** AAD 绑定用户与字段，同一密文不能搬到其他用户或收货字段下使用。 */
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

    private byte[] aad(Long userId, String field) {
        return ("addresses:" + userId + ":" + field).getBytes(StandardCharsets.UTF_8);
    }
}
