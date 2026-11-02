package com.shiwushop.slice.media;
import com.shiwushop.support.ControllerSliceSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import com.shiwushop.common.GlobalExceptionHandler;

import com.shiwushop.common.media.controller.MediaController;
import com.shiwushop.common.media.service.MediaReadService;
import com.shiwushop.common.media.service.MediaService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MediaController.class)
@ContextConfiguration(classes={MediaController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class MediaControllerSliceTests {
    @Test void fullWaitingRoomReturns429AndRetryAfter() throws Exception {
        var session = new MockHttpSession(); session.setAttribute("userId", 3L);
        var file = new MockMultipartFile("file", "毛巾.png", "image/png", new byte[]{1});
        when(uploads.upload(3L, file)).thenThrow(new com.shiwushop.common.BusinessException(
                429, "图片处理繁忙，请稍后重试", java.util.Map.of("retryAfterSeconds", 1), null));
        mvc.perform(multipart("/media").file(file).with(csrf()).session(session))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"));
    }
    @MockitoBean private MediaReadService service;
    @MockitoBean private MediaService uploads;
    @Autowired private MockMvc mvc;

    @Test
    void uploadKeepsTheExistingMediaRoute() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", 3L);
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1});
        when(uploads.upload(3L, file)).thenReturn(8L);

        mvc.perform(multipart("/media").file(file).with(csrf()).session(session))
                .andExpect(status().isOk());
    }

    @Test
    void revalidatesPublicImageWithoutSendingBodyAgain() throws Exception {
        when(service.read(7L, null)).thenReturn(new MediaReadService.ImageFile(
                new ByteArrayResource(new byte[]{1, 2, 3}), "image/png", false, "v7"));
        mvc.perform(get("/media/7"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("ETag", "\"v7\""))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
        mvc.perform(get("/media/7").header("If-None-Match", "\"v7\""))
                .andExpect(status().isNotModified())
                .andExpect(content().bytes(new byte[0]));
    }
}
