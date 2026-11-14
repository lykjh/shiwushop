package com.shiwushop.unit.member;

import com.shiwushop.member.dto.PointAdjustment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class PointAdjustmentJsonTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1.0", "1e2", "\"1\"", "true", "9223372036854775808", "-9223372036854775809"})
    void nonIntegerTokensAndOutOfRangeNumbersAreRejected(String value) {
        assertThrows(JacksonException.class,
                () -> json.readValue("{\"deltaPointUnits\":" + value + "}", PointAdjustment.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, -1, Long.MAX_VALUE, Long.MIN_VALUE})
    void integerTokensKeepTheirExactValue(long value) {
        assertEquals(value,
                json.readValue("{\"deltaPointUnits\":" + value + "}", PointAdjustment.class).deltaPointUnits());
    }
}
