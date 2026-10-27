package com.shiwushop.search;

import com.shiwushop.catalog.search.DiscoveryCursor;
import com.shiwushop.common.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscoveryCursorTests {
    private final DiscoveryCursor codec = new DiscoveryCursor(new ObjectMapper(), "test-cursor-secret-with-32-characters");

    @Test
    void bindsQueryAndIdentityAndRejectsTampering() {
        var state = new DiscoveryCursor.State("pit", List.of(1.0, 5), "filters", 8L,
                Instant.now().plusSeconds(120).getEpochSecond());
        String token = codec.encode(state);
        assertEquals(state, codec.decode(token, "filters", 8L));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> codec.decode(token, "other", 8L)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> codec.decode(token, "filters", 9L)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> codec.decode(token + "x", "filters", 8L)).getCode());
    }

    @Test
    void expiredCursorRequiresRestart() {
        var state = new DiscoveryCursor.State("pit", List.of(5), "filters", null,
                Instant.now().minusSeconds(1).getEpochSecond());
        assertEquals(410, assertThrows(BusinessException.class,
                () -> codec.decode(codec.encode(state), "filters", null)).getCode());
    }
}
