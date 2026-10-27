package com.shiwushop.search;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DiscoveryControllerTests {
    @Autowired private MockMvc mvc;

    @Test
    void pilotIndexIsNotExposedAndNumericPagesAreRejected() throws Exception {
        mvc.perform(get("/discovery/products"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
        mvc.perform(get("/discovery/products").param("page", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/discovery/products").param("afterId", "123"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/search/suggestions").param("q", "商品"))
                .andExpect(status().isServiceUnavailable());
    }
}
