package com.shiwushop.unit.member;

import com.shiwushop.member.MemberSettings;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class MemberSettingsTest {
    @Test
    void disabledFeatureNeedsNoActivationDate() {
        assertFalse(new MemberSettings(false, "").enabled());
    }

    @Test
    void enabledFeatureCannotGuessActivationDate() {
        assertThrows(IllegalArgumentException.class, () -> new MemberSettings(true, ""));
    }

    @Test
    void activationDateIsFixedAndCannotBeUtcOrHostDefault() {
        assertEquals(LocalDateTime.parse("2026-11-01T00:00:00"),
                new MemberSettings(true, "2026-11-01T00:00:00").rewardStartAt());
        assertThrows(java.time.format.DateTimeParseException.class,
                () -> new MemberSettings(true, "2026-11-01T00:00:00Z"));
    }
}
