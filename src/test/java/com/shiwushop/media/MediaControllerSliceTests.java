package com.shiwushop.media;

import com.shiwushop.common.media.controller.MediaReadController;
import com.shiwushop.common.media.controller.MediaUploadController;
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

class MediaControllerSliceTests {
    private final MediaReadService service = mock(MediaReadService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MediaReadController(service)).build();

    @Test
    void uploadKeepsTheExistingMediaRoute() throws Exception {
        MediaService uploads = mock(MediaService.class);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", 3L);
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1});
        when(uploads.upload(3L, file)).thenReturn(8L);

        MockMvcBuilders.standaloneSetup(new MediaUploadController(uploads)).build()
                .perform(multipart("/media").file(file).session(session))
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
