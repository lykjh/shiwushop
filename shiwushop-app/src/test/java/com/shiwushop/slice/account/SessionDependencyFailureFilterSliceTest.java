package com.shiwushop.slice.account;

import com.shiwushop.common.auth.SessionDependencyFailureFilter;
import com.shiwushop.support.ControllerSliceSecurity;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.HeaderHttpSessionIdResolver;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真正的 Spring Session 过滤器，仅在存储边界注入读取／保存故障。 */
@WebMvcTest(SessionDependencyFailureFilterSliceTest.Probe.class)
@ContextConfiguration(classes = {SessionDependencyFailureFilterSliceTest.Probe.class,
        SessionDependencyFailureFilterSliceTest.Configuration.class,
        SessionDependencyFailureFilter.class, ControllerSliceSecurity.class})
class SessionDependencyFailureFilterSliceTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionRepository<MapSession> repository;
    @BeforeEach void session() {when(repository.createSession()).thenAnswer(call -> new MapSession());}

    @Test void readFailureBeforeControllerIs503() throws Exception {
        when(repository.findById("existing")).thenThrow(new RedisConnectionFailureException("offline"));
        mvc.perform(get("/test/session-dependency").header("X-Test-Session", "existing"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503));
    }
    @Test void saveFailureAtResponseCompletionIs503RatherThanBufferedSuccess() throws Exception {
        doThrow(new RedisConnectionFailureException("offline")).when(repository).save(any());
        mvc.perform(get("/test/session-dependency"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.success").doesNotExist());
    }
    @Test void healthyRepositoryKeepsNormalResponseAndSavesSession() throws Exception {
        mvc.perform(get("/test/session-dependency"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(repository, atLeastOnce()).save(any());
    }
    @TestConfiguration static class Configuration {
        @Bean SessionRepositoryFilter<MapSession> testSessionFilter(SessionRepository<MapSession> repository) {
            var filter = new SessionRepositoryFilter<>(repository);
            filter.setHttpSessionIdResolver(new HeaderHttpSessionIdResolver("X-Test-Session"));
            return filter;
        }
    }
    @RestController static class Probe {
        @GetMapping("/test/session-dependency") Map<String,Boolean> session(HttpServletRequest request) {
            request.getSession(true).setAttribute("probe", "saved");
            return Map.of("success", true);
        }
    }
}
