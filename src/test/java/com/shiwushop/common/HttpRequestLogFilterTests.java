package com.shiwushop.common;

import com.shiwushop.common.logging.HttpRequestLogFilter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRequestLogFilterTests {
    @Test
    void logsRouteAndStatusWithoutSensitiveRequestValues() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(HttpRequestLogFilter.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/payments/checkout/secret-trade-no");
            request.setQueryString("token=secret-token");
            request.addHeader("Cookie", "MALLSESSION=secret-session");
            MockHttpServletResponse response = new MockHttpServletResponse();

            new HttpRequestLogFilter().doFilter(request, response, (req, res) -> {
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/payments/checkout/{outTradeNo}");
                ((MockHttpServletResponse) res).setStatus(404);
            });

            String message = events.list.getLast().getFormattedMessage();
            assertThat(response.getHeader("X-Request-Id")).isNotBlank();
            assertThat(message).contains("method=GET", "route=/payments/checkout/{outTradeNo}", "status=404",
                    "requestId=" + response.getHeader("X-Request-Id"));
            assertThat(message).doesNotContain("secret-trade-no", "secret-token", "secret-session");
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }
}
