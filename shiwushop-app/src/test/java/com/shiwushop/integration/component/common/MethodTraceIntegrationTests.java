package com.shiwushop.integration.component.common;

import com.shiwushop.common.logging.MethodTraceAspect;
import com.shiwushop.common.logging.HttpRequestLogFilter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shiwushop.product.controller.product.ProductController;
import com.shiwushop.marketing.MarketingSettings;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shop.logging.method-enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MethodTraceIntegrationTests {
    @LocalServerPort
    private int port;
    @Autowired
    private ProductController productController;
    @Autowired
    private MarketingSettings marketingSettings;

    @Test
    void tracesControllerAndCacheDuringRealHttpRequest() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(MethodTraceAspect.class);
        Logger httpLogger = (Logger) LoggerFactory.getLogger(HttpRequestLogFilter.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        ListAppender<ILoggingEvent> httpEvents = new ListAppender<>();
        events.start();
        httpEvents.start();
        logger.addAppender(events);
        httpLogger.addAppender(httpEvents);
        try {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/products?size=1")).GET().build();
            HttpResponse<Void> response = HttpClient.newHttpClient().send(request,
                    HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(200);
            String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();

            String messages = events.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(AopUtils.isAopProxy(productController)).isTrue();
            assertThat(AopUtils.isAopProxy(marketingSettings)).isFalse();
            assertThat(messages).contains("ProductController.list outcome=success",
                    "PublicProductCache.list outcome=success",
                    "PublicProductServiceImpl.publicList outcome=success",
                    "ProductMapper.selectPublicPage outcome=success");
            assertThat(events.list).anySatisfy(event ->
                    assertThat(event.getMDCPropertyMap().get("requestId")).isEqualTo(requestId));
            assertThat(httpEvents.list).anySatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("module=product", "requestId=" + requestId));
        } finally {
            logger.detachAppender(events);
            httpLogger.detachAppender(httpEvents);
            events.stop();
            httpEvents.stop();
        }
    }
}
