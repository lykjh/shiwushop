package com.shiwushop.slice.user;
import com.shiwushop.support.ControllerSliceSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.shiwushop.user.controller.user.PhoneBindingController;
import com.shiwushop.user.controller.user.SmsLoginController;
import com.shiwushop.user.service.PhoneBindingService;
import com.shiwushop.user.service.SmsLoginService;
import com.shiwushop.user.vo.user.LoginVO;
import com.shiwushop.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PhoneBindingController.class, SmsLoginController.class})
@ContextConfiguration(classes={PhoneBindingController.class, SmsLoginController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class SmsControllerRoutesTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private PhoneBindingService binding;
    @MockitoBean private SmsLoginService login;


    @Test
    void bindingRoutesKeepAccountIdentityAndInvalidateSessionAfterConfirmation() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", 7L);
        mvc.perform(post("/users/phone/bind/send").with(csrf()).session(session).remoteAddress("192.0.2.7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"13800000000\"}"))
                .andExpect(status().isOk());
        verify(binding).sendCode(7L, "13800000000", "192.0.2.7");

        mvc.perform(post("/users/phone/bind/confirm").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Shop123!\",\"phone\":\"13800000000\",\"code\":\"123456\"}"))
                .andExpect(status().isOk());
        verify(binding).bind(7L, "Shop123!", "13800000000", "123456");
        assertTrue(session.isInvalid());
    }

    @Test
    void anonymousSmsRoutesKeepResponseAndUserId() throws Exception {
        when(login.login(org.mockito.ArgumentMatchers.eq("13800000000"),
                org.mockito.ArgumentMatchers.eq("123456"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new LoginVO(7L, "buyer"));
        mvc.perform(post("/users/sms/send").with(csrf()).remoteAddress("192.0.2.8")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"13800000000\"}"))
                .andExpect(status().isOk());
        verify(login).sendCode("13800000000", "192.0.2.8");

        mvc.perform(post("/users/sms/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000000\",\"code\":\"123456\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(7));
    }
}
