package com.shiwushop.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MethodTraceAspectTests {
    @Test
    void recordsSuccessAndFailureWithoutValues() {
        Logger logger = (Logger) LoggerFactory.getLogger(MethodTraceAspect.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            AspectJProxyFactory factory = new AspectJProxyFactory(new ExampleService());
            factory.addAspect(new MethodTraceAspect());
            ExampleService service = factory.getProxy();

            assertThat(service.success("secret-input")).isEqualTo("secret-output");
            assertThatThrownBy(() -> service.fail("secret-error"))
                    .isInstanceOf(IllegalStateException.class);

            String messages = events.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(messages).contains("ExampleService.success outcome=success", "ExampleService.fail outcome=failure",
                    "error=IllegalStateException", "durationMs=");
            assertThat(messages).doesNotContain("secret-input", "secret-output", "secret-error");
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    public static class ExampleService {
        public String success(String input) {
            return "secret-output";
        }

        public void fail(String input) {
            throw new IllegalStateException("secret-error");
        }
    }
}
