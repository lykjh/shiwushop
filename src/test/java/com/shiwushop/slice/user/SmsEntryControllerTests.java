package com.shiwushop.slice.user;
import com.shiwushop.support.ControllerSliceSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.shiwushop.user.controller.user.SmsEntryController;
import com.shiwushop.user.service.SmsEntryService;
import com.shiwushop.user.vo.user.LoginVO;
import com.shiwushop.user.vo.user.SmsEntryVerifyVO;
import com.shiwushop.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SmsEntryController.class)
@ContextConfiguration(classes={SmsEntryController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class SmsEntryControllerTests {
    @MockitoBean private SmsEntryService service;
    @Autowired private MockMvc mvc;

    @Test
    void threeRoutesForwardValidatedFieldsAndKeepTheirResponseShapes() throws Exception {
        when(service.verify(org.mockito.ArgumentMatchers.eq("13800000000"),
                org.mockito.ArgumentMatchers.eq("123456"), any()))
                .thenReturn(SmsEntryVerifyVO.setPassword("ticket"));
        when(service.register(org.mockito.ArgumentMatchers.eq("13800000000"),
                org.mockito.ArgumentMatchers.eq("ticket"), org.mockito.ArgumentMatchers.eq("Shop123!"), any()))
                .thenReturn(new LoginVO(7L, "swaccount"));

        mvc.perform(post("/users/sms/entry/send").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800000000\"}"))
                .andExpect(status().isOk());
        verify(service).sendCode(org.mockito.ArgumentMatchers.eq("13800000000"), any());
        mvc.perform(post("/users/sms/entry/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800000000\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SET_PASSWORD_REQUIRED"));
        mvc.perform(post("/users/sms/entry/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800000000\",\"registrationTicket\":\"ticket\",\"password\":\"Shop123!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("swaccount"));
    }
}
