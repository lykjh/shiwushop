package com.shiwushop.integration.account;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.HttpRequestLogFilter;
import com.shiwushop.support.HttpAccountClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 使用测试专属端点核验真实 HTTP 指标和日志，生产接口不增加故障入口。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@Import(RuntimeObservabilityHttpTest.Probe.class)
class RuntimeObservabilityHttpTest {
    @LocalServerPort int port;
    @Autowired MeterRegistry meters;

    @Test void expectedRejectionsAndUnexpectedFailuresHaveSeparateStatusMetricsAndSafeLogs() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(HttpRequestLogFilter.class);
        var records = new ListAppender<ILoggingEvent>(); records.start(); logger.addAppender(records);
        String secret = "DoNotLogSecret_741!";
        try (var client = new HttpAccountClient("http://127.0.0.1:" + port)) {
            for (int status : new int[]{400,500}) {
                var response = client.get("/test/observability/" + status + "?password=" + secret);
                assertEquals(status, response.statusCode());
                assertTrue(response.headers().firstValue("X-Request-Id").orElseThrow().length() > 10);
                assertFalse(response.body().contains("internal-detail"));
                // 指标在请求收尾记录；轮询只等待可观测性提交，不改变业务时序。
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (meters.find("http.server.requests").tag("uri", "/test/observability/{status}").tag("status", ""+status).timer() == null && System.nanoTime() < deadline) Thread.sleep(10);
                var timer = meters.find("http.server.requests").tag("uri", "/test/observability/{status}").tag("status", ""+status).timer();
                assertNotNull(timer); assertTrue(timer.count() >= 1);
            }
            var messages = records.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertEquals(2, messages.stream().filter(m -> m.contains("route=/test/observability/{status}")).count());
            assertTrue(messages.stream().noneMatch(m -> m.contains(secret) || m.contains("password=")));
        } finally {logger.detachAppender(records); records.stop();}
    }

    @RestController static class Probe {
        @GetMapping("/test/observability/{status}") Map<String,Object> fail(@PathVariable int status) {
            if (status == 400) throw new BusinessException(400, "参数校验失败");
            throw new IllegalStateException("internal-detail");
        }
    }
}
