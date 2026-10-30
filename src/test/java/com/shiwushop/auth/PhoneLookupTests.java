package com.shiwushop.auth;

import com.shiwushop.account.auth.PhoneLookup;
import com.shiwushop.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneLookupTests {
    @Test
    void stableLookupAndPurposeSeparatedCodeMac() {
        String key = Base64.getEncoder().encodeToString("A".repeat(32).getBytes(StandardCharsets.UTF_8));
        PhoneLookup lookup = new PhoneLookup(key);
        String first = lookup.digest("13800138000");
        assertThat(first).hasSize(64).isEqualTo(lookup.digest("13800138000"));
        assertThat(first).isNotEqualTo(lookup.digest("13900139000"));
        assertThat(lookup.codeMac("bind", first, "123456"))
                .isNotEqualTo(lookup.codeMac("login", first, "123456"));
    }

    @Test
    void missingLookupKeyFailsClosed() {
        assertThatThrownBy(() -> new PhoneLookup("").digest("13800138000"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(503);
    }
}
