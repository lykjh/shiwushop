package com.shiwushop;

import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@Configuration
class TestCsrfConfig {
    @Bean
    MockMvcBuilderCustomizer csrfForExistingMockMvcTests() {
        return builder -> builder.defaultRequest(get("/").with(csrf()));
    }
}
