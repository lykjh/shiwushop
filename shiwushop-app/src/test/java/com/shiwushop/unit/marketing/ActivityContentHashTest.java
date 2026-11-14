package com.shiwushop.unit.marketing;

import com.shiwushop.marketing.ActivityContentHash;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ActivityContentHashTest {
    final ActivityContentHash hash = new ActivityContentHash(new ObjectMapper());

    @Test
    void equivalentMoneyTimeAndObjectOrderUseOneDigest() {
        var first = new LinkedHashMap<String, Object>();
        first.put("startAt", "2035-01-01T00:00:00.123456+08:00");
        first.put("amount", new BigDecimal("9999999999999999.90"));
        var second = new LinkedHashMap<String, Object>();
        second.put("amount", new BigDecimal("9999999999999999.9"));
        second.put("startAt", "2034-12-31T16:00:00.123Z");
        assertArrayEquals(hash.hash(first), hash.hash(second));
        second.put("amount", new BigDecimal("9999999999999999.91"));
        assertFalse(Arrays.equals(hash.hash(first), hash.hash(second)));
    }

    @Test
    void arrayOrderAndExplicitNullRemainPartOfTheRequest() {
        assertNotEquals(hash.etag(List.of(1, 2)), hash.etag(List.of(2, 1)));
        var withNull = new HashMap<String, Object>();
        withNull.put("x", null);
        assertNotEquals(hash.etag(withNull), hash.etag(Map.of()));
        assertTrue(hash.etag(withNull).matches("\"[0-9a-f]{64}\""));
    }
}
