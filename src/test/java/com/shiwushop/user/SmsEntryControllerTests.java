package com.shiwushop.user;

import com.shiwushop.account.controller.user.SmsEntryController;
import com.shiwushop.account.service.SmsEntryService;
import com.shiwushop.account.vo.user.LoginVO;
import com.shiwushop.account.vo.user.SmsEntryVerifyVO;
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

class SmsEntryControllerTests {
    private final SmsEntryService service = mock(SmsEntryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SmsEntryController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void threeRoutesForwardValidatedFieldsAndKeepTheirResponseShapes() throws Exception {
        when(service.verify(org.mockito.ArgumentMatchers.eq("13800000000"),
                org.mockito.ArgumentMatchers.eq("123456"), any()))
                .thenReturn(SmsEntryVerifyVO.setPassword("ticket"));
        when(service.register(org.mockito.ArgumentMatchers.eq("13800000000"),
                org.mockito.ArgumentMatchers.eq("ticket"), org.mockito.ArgumentMatchers.eq("Shop123!"), any()))
                .thenReturn(new LoginVO(7L, "swaccount"));

        mvc.perform(post("/users/sms/entry/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800000000\"}"))
                .andExpect(status().isOk());
        verify(service).sendCode(org.mockito.ArgumentMatchers.eq("13800000000"), any());
        mvc.perform(post("/users/sms/entry/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800000000\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SET_PASSWORD_REQUIRED"));
        mvc.perform(post("/users/sms/entry/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800000000\",\"registrationTicket\":\"ticket\",\"password\":\"Shop123!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("swaccount"));
    }
}
