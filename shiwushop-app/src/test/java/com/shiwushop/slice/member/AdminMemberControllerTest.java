package com.shiwushop.slice.member;

import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.member.controller.AdminMemberController;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.member.service.MemberAdministrationService;
import com.shiwushop.support.ControllerSliceSecurity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminMemberController.class)
@ContextConfiguration(classes = {AdminMemberController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class AdminMemberControllerTest {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    MemberAccountService accounts;
    @MockitoBean
    MemberAdministrationService administration;

    static Stream<String> validKeys() {
        return Stream.of("!", "~", "Case:[]{}()/\\+.,;=?!@#$%^&*<>|\"'`", "X".repeat(64));
    }

    static Stream<String> invalidKeys() {
        return Stream.of("", "X".repeat(65), "a b", " a", "a ", "a\tb", "a\nb", "a\rb", "a\u007fb", "键");
    }

    @ParameterizedTest
    @MethodSource("validKeys")
    void bothCommandsPreserveVisibleAsciiKey(String key) throws Exception {
        for (String kind : new String[]{"growth", "point"}) {
            String body = kind.equals("growth") ? "{\"deltaAmount\":1,\"reason\":\"修正\",\"evidenceRef\":\"E1\"}"
                    : "{\"deltaPointUnits\":-1,\"reason\":\"修正\",\"evidenceRef\":\"E1\"}";
            mvc.perform(post("/admin/members/7/" + kind + "-adjustments").sessionAttr("userId", 42L)
                            .with(csrf()).header("Idempotency-Key", key).contentType("application/json").content(body))
                    .andExpect(status().isOk());
        }
        verify(administration).adjustGrowth(eq(42L), eq(7L), eq(key), any());
        verify(administration).adjustPoints(eq(42L), eq(7L), eq(key), any());
    }

    @ParameterizedTest
    @MethodSource("invalidKeys")
    void bothCommandsRejectInvalidKeyBeforeCallingService(String key) throws Exception {
        for (String kind : new String[]{"growth", "point"}) {
            String body = kind.equals("growth") ? "{\"deltaAmount\":1,\"reason\":\"修正\",\"evidenceRef\":\"E1\"}"
                    : "{\"deltaPointUnits\":-1,\"reason\":\"修正\",\"evidenceRef\":\"E1\"}";
            mvc.perform(post("/admin/members/7/" + kind + "-adjustments").sessionAttr("userId", 42L)
                            .with(csrf()).header("Idempotency-Key", key).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(administration, accounts);
    }
}
