package com.shiwushop.slice.user;

import com.shiwushop.support.ControllerSliceSecurity;
import com.shiwushop.user.auth.JwtLogin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.shiwushop.user.controller.user.PhoneBindingController;
import com.shiwushop.user.service.PhoneBindingService;
import com.shiwushop.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static com.shiwushop.support.MockLogin.loginAs;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PhoneBindingController.class})
@ContextConfiguration(classes = {PhoneBindingController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class SmsControllerRoutesTests {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private PhoneBindingService binding;
    @MockitoBean
    private JwtLogin jwt;

    @Test
    void bindingRoutesKeepAccountIdentityAndClearJwtAfterConfirmation() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/users/phone/bind/send").with(csrf().asHeader()).session(session).with(loginAs(7L)).remoteAddress("192.0.2.7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"13800000000\"}"))
                .andExpect(status().isOk());
        verify(binding).sendCode(7L, "13800000000", "192.0.2.7");

        mvc.perform(post("/users/phone/bind/confirm").with(csrf().asHeader()).session(session).with(loginAs(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Shop123!\",\"phone\":\"13800000000\",\"code\":\"123456\"}"))
                .andExpect(status().isOk());
        verify(binding).bind(7L, "Shop123!", "13800000000", "123456");
        verify(jwt).clearCookie(any());
        assertFalse(session.isInvalid());
    }
}
