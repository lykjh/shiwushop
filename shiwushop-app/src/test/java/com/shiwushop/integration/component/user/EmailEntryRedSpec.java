package com.shiwushop.integration.component.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 邮箱入口施工前的公开 HTTP 契约；实现后改名纳入常规回归。 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=2")
@AutoConfigureMockMvc
class EmailEntryRedSpec {
    @Autowired private MockMvc mvc;

    @Test
    void malformedEmailCannotRequestLoginCode() throws Exception {
        mvc.perform(post("/users/email/entry/send").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fields.email").isNotEmpty());
    }
}
