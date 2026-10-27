package com.shiwushop.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RetiredLegacyProductRouteTests {
    @Autowired private MockMvc mockMvc;

    @Test
    void unassignedProductsRouteIsGone() throws Exception {
        mockMvc.perform(get("/admin/products/unassigned"))
                .andExpect(status().isNotFound());
    }

    @Test
    void legacyAssignmentRouteIsGone() throws Exception {
        mockMvc.perform(put("/admin/products/1/shop"))
                .andExpect(status().isNotFound());
    }
}
