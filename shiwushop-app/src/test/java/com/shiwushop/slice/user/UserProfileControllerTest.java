package com.shiwushop.slice.user;

import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.support.ControllerSliceSecurity;
import com.shiwushop.user.controller.user.UserProfileController;
import com.shiwushop.user.service.UserProfileService;
import com.shiwushop.user.vo.user.UserProfileVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserProfileController.class)
@ContextConfiguration(classes={UserProfileController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class UserProfileControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserProfileService service;
    @Test void readsAndUpdatesOnlySessionUser() throws Exception {
        when(service.get(7L)).thenReturn(new UserProfileVO("旧", null));
        when(service.update(eq(7L), any())).thenReturn(new UserProfileVO("新", 5L));
        mvc.perform(get("/users/profile").sessionAttr("userId",7L)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("旧"));
        mvc.perform(put("/users/profile").sessionAttr("userId",7L).with(csrf()).contentType("application/json")
                .content("{\"nickname\":\" 新 \",\"avatarMediaId\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.avatarMediaId").value(5))
                .andExpect(jsonPath("$.data.role").doesNotExist());
        verify(service).update(eq(7L), argThat(dto -> dto.nickname().equals("新")));
    }
    @Test void rejectsAnonymousInvalidAndMissingCsrf() throws Exception {
        mvc.perform(get("/users/profile")).andExpect(status().isUnauthorized());
        mvc.perform(put("/users/profile").sessionAttr("userId",7L).contentType("application/json")
                .content("{\"nickname\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/users/profile").sessionAttr("userId",7L).with(csrf()).contentType("application/json")
                .content("{\"nickname\":\"　\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
