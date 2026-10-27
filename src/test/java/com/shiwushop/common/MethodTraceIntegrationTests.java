package com.shiwushop.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shiwushop.controller.product.ProductController;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "shop.logging.method-enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MethodTraceIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private ProductController productController;

    @Test
    void tracesControllerAndCacheDuringRealRequest() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(MethodTraceAspect.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            String requestId = mockMvc.perform(get("/products").param("size", "1"))
                    .andExpect(status().isOk()).andReturn().getResponse().getHeader("X-Request-Id");

            String messages = events.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(AopUtils.isAopProxy(productController)).isTrue();
            assertThat(messages).contains("ProductController.list outcome=success", "PublicProductCache.list outcome=success",
                    "ProductServiceImpl.publicList outcome=success", "ProductMapper.selectPublicPage outcome=success");
            assertThat(events.list).anySatisfy(event ->
                    assertThat(event.getMDCPropertyMap().get("requestId")).isEqualTo(requestId));
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }
}
