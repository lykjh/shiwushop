package com.shiwushop.integration.component.search;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DiscoveryControllerTests {
    @Autowired private MockMvc mvc;
    @Autowired private Environment environment;

    /** 本地启动配置不能让默认测试打开消费者或连接商城的搜索服务。 */
    @Test
    void searchTestDefaultsStayIsolatedFromLocalConfiguration() {
        assertEquals("http://127.0.0.1:29200", environment.getRequiredProperty("shop.search.url"));
        assertEquals(false, environment.getRequiredProperty("shop.search.discovery-enabled", Boolean.class));
        assertEquals(false, environment.getRequiredProperty("shop.search.worker-enabled", Boolean.class));
    }

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
