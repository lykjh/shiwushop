package com.shiwushop.account.service.impl;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RandomUsernameTests {
    @Test
    void namesHaveOnlyLettersAndDigitsAndAreDistinct() {
        RandomUsername generator = new RandomUsername();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String name = generator.next();
            assertThat(name).matches("sw[a-z0-9]{20}");
            names.add(name);
        }
        assertThat(names).hasSize(100);
    }
}
