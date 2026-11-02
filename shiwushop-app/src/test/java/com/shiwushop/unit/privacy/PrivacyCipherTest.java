package com.shiwushop.unit.privacy;

import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.common.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 真实加解密验证异常分类；不连接数据库。 */
class PrivacyCipherTest {
    private final PrivacyCipher cipher = new PrivacyCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");

    @Test void encryptedChineseAddressRoundTrips() {
        var encrypted = cipher.encrypt(1L, "consignee", "张三");
        assertEquals("张三", cipher.decrypt(1L, "consignee", 1, encrypted));
    }

    @Test void tamperedCiphertextAndWrongOwnerAreRejected() {
        var encrypted = cipher.encrypt(1L, "consignee", "张三");
        assertEquals(500, assertThrows(BusinessException.class,
                () -> cipher.decrypt(2L, "consignee", 1, encrypted)).getCode());
        encrypted[encrypted.length - 1] ^= 1;
        assertEquals(500, assertThrows(BusinessException.class,
                () -> cipher.decrypt(1L, "consignee", 1, encrypted)).getCode());
    }

    @Test void missingAndInvalidKeysRemainConfigurationErrors() {
        for (String key : new String[]{"", "not-base64", "YWJj"}) {
            assertEquals(503, assertThrows(BusinessException.class,
                    () -> new PrivacyCipher(key).encrypt(1L, "consignee", "张三")).getCode());
        }
    }
}
