package com.shiwushop.slice.member;

import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.member.controller.MemberController;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.support.ControllerSliceSecurity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MemberController.class)
@ContextConfiguration(classes = {MemberController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class MemberControllerTest {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    MemberAccountService accounts;

    @Test
    void levelRulesArePublic() throws Exception {
        mvc.perform(get("/members/levels")).andExpect(status().isOk());
        verify(accounts).levels();
    }

    @Test
    void anonymousCannotReadBalances() throws Exception {
        mvc.perform(get("/members/me")).andExpect(status().isUnauthorized());
        verifyNoInteractions(accounts);
    }

    @Test
    void identityComesFromSessionNotQueryParameter() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("userId", 42L);
        mvc.perform(get("/members/me").session(session).param("userId", "99")).andExpect(status().isOk());
        verify(accounts).overview(42L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "oops"})
    void invalidPageSizeStopsAtHttpBoundary(String size) throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("userId", 42L);
        mvc.perform(get("/members/me/point-entries").session(session).param("limit", size))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(accounts);
    }

    @ParameterizedTest
    @CsvSource({"point-lots,size,0", "point-lots,size,101", "point-lots,afterId,0", "point-lots,status,OTHER",
            "reward-tasks,rewardKind,OTHER", "reward-tasks,status,OTHER", "reward-tasks,orderId,-1",
            "reward-tasks,afterId,0", "reward-tasks,size,101"})
    void newQueryParametersAreValidatedOnceAtHttpBoundary(String path, String parameter,
                                                          String value) throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("userId", 42L);
        mvc.perform(get("/members/me/" + path).session(session).param(parameter, value))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(accounts);
    }
}
