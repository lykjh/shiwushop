package com.shiwushop.unit.account;

import com.shiwushop.user.dto.user.RegisterDTO;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** 直接运行 Bean Validation，无 Spring、数据库或 HTTP。 */
class RegisterDTOTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    @AfterAll static void closeValidator() { FACTORY.close(); }

    static Stream<String> validUsernames() {
        return Stream.of("shop", "A".repeat(100), "buyer_zhang.2026-01");
    }
    static Stream<String> validPasswords() { return Stream.of("S".repeat(8), "S".repeat(72)); }
    static Stream<String> invalidPasswords() { return Stream.of(null, "", "        ", "S".repeat(7), "S".repeat(73), "S".repeat(1000), "asdfg密123456", "Shop😀123!", " Shop123!", "Shop123! ", "Shop\t123!", "Shop\n123!", "Shop\u007f123", "Shop\u0000123"); }

    @ParameterizedTest @MethodSource("validUsernames")
    void acceptsUsernameBoundaries(String username) {
        assertTrue(FACTORY.getValidator().validate(new RegisterDTO(username, "Shop123!", null)).isEmpty());
    }
    @ParameterizedTest @MethodSource("validPasswords")
    void acceptsPasswordLengthBoundaries(String password) {
        assertTrue(FACTORY.getValidator().validate(new RegisterDTO("buyer_zhang", password, null)).isEmpty());
    }
    @ParameterizedTest @MethodSource("invalidPasswords")
    void rejectsMissingBlankAndOversizedPasswords(String password) {
        assertTrue(FACTORY.getValidator().validate(new RegisterDTO("buyer_zhang", password, null)).stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("password")));
    }
    @ParameterizedTest @NullSource
    @ValueSource(strings = {"", "    ", "abc", "buyer zhang", " 买家", "buyer@shop", "buyer😀"})
    void rejectsInvalidUsernames(String username) { assertInvalidUsername(username); }

    @org.junit.jupiter.api.Test void rejectsUsernameAboveMaximum() { assertInvalidUsername("a".repeat(101)); }
    private void assertInvalidUsername(String username) {
        assertTrue(FACTORY.getValidator().validate(new RegisterDTO(username, "Shop123!", null)).stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("username")));
    }
}
