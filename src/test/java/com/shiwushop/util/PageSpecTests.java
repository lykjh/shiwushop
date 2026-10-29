package com.shiwushop.util;

import com.shiwushop.common.pagination.PageSpec;

import com.shiwushop.common.BusinessException;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageSpecTests {
    @Test
    void validatesBoundsAndUsesLongOffset() {
        assertEquals(214748364600L, PageSpec.of(Integer.MAX_VALUE, 100).offset());
        assertEquals(0L, PageSpec.of(1, 20).offset());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> PageSpec.of(0, 20)).getCode());
        assertEquals("店铺或分页参数错误", assertThrows(BusinessException.class,
                () -> PageSpec.of(1, 101, "店铺或分页参数错误")).getMessage());
    }
}
