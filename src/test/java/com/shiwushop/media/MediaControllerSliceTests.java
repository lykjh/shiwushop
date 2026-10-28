package com.shiwushop.media;

import com.shiwushop.controller.media.MediaController;
import com.shiwushop.service.MediaService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MediaControllerSliceTests {
    private final MediaService service = mock(MediaService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MediaController(service)).build();

    @Test
    void revalidatesPublicImageWithoutSendingBodyAgain() throws Exception {
        when(service.read(7L, null)).thenReturn(new MediaService.ImageFile(
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
