package com.shiwushop.slice.catalog;

import com.shiwushop.product.controller.review.ReviewController;
import com.shiwushop.product.service.ReviewService;
import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.support.ControllerSliceSecurity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 非法评分、集合元素和路径必须在进入评价服务前被拒绝。 */
@WebMvcTest(ReviewController.class)
@ContextConfiguration(classes = {ReviewController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class ReviewControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ReviewService service;

    @ParameterizedTest
    @ValueSource(strings = {"{\"rating\":0,\"content\":\"正常评价\"}",
            "{\"rating\":6,\"content\":\"正常评价\"}", "{\"rating\":5,\"content\":\" \"}",
            "{\"rating\":5,\"content\":\"正常评价\",\"imageMediaIds\":[null]}",
            "{\"rating\":5,\"content\":\"正常评价\",\"imageMediaIds\":[-1]}"})
    void rejectsBodyBeforeBusinessService(String body) throws Exception {
        mvc.perform(post("/orders/1/items/1/review").with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
