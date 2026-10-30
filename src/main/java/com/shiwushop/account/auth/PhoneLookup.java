package com.shiwushop.account.auth;

import com.shiwushop.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class PhoneLookup {
    private final String keyBase64;

    public PhoneLookup(@Value("${shop.sms.lookup-key-base64:}") String keyBase64) {
        this.keyBase64 = keyBase64;
    }

    /** 用独立密钥生成稳定查询摘要；相同手机号才能命中同一条绑定。 */
    public String digest(String phone) {
        return mac("lookup:" + phone);
    }

    /** 验证码的用途及手机号均参与摘要，防止绑定码被用于登录。 */
    public String codeMac(String purpose, String phoneDigest, String code) {
        return mac("code:" + purpose + ":" + phoneDigest + ":" + code);
    }

    private String mac(String value) {
        try {
            byte[] key = Base64.getDecoder().decode(keyBase64);
            if (key.length != 32) throw new IllegalArgumentException("invalid key length");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException(503, "短信登录配置不可用", e);
        }
    }
}
