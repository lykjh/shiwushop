package com.shiwushop.unit.account;

import com.shiwushop.user.dto.user.UserProfileUpdateDTO;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserProfileUpdateDTOTest {
    @Test void stripsUnicodeWhitespaceBeforeNicknameValidation() {
        assertEquals("小明", new UserProfileUpdateDTO("\u3000 小明 \u3000", null).nickname());
    }
    @Test void validatesCodePointsAndOptionalAvatar() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new UserProfileUpdateDTO("😀".repeat(40), null)).isEmpty());
            assertTrue(validator.validate(new UserProfileUpdateDTO("x", 1L)).isEmpty());
            assertFalse(validator.validate(new UserProfileUpdateDTO("😀".repeat(41), null)).isEmpty());
            assertFalse(validator.validate(new UserProfileUpdateDTO("　 ", null)).isEmpty());
            assertFalse(validator.validate(new UserProfileUpdateDTO(null, null)).isEmpty());
            assertFalse(validator.validate(new UserProfileUpdateDTO("x", 0L)).isEmpty());
        }
    }
}
