package com.shiwushop.slice.review;
import com.shiwushop.support.ControllerSliceSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import com.shiwushop.common.GlobalExceptionHandler;

import com.shiwushop.product.controller.review.ProductReviewController;
import com.shiwushop.product.service.PublicReviewService;
import com.shiwushop.product.vo.review.ReviewSummaryVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductReviewController.class)
@ContextConfiguration(classes={ProductReviewController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class ReviewSummaryControllerSliceTests {
    @MockitoBean private PublicReviewService reviews;
    @Autowired private MockMvc mvc;

    @Test
    void summaryEndpointKeepsItsPublicResponseShape() throws Exception {
        when(reviews.summary(9L)).thenReturn(new ReviewSummaryVO(2, new BigDecimal("4.50")));

        mvc.perform(get("/products/9/review-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewCount").value(2))
                .andExpect(jsonPath("$.data.averageRating").value(4.50));
    }
}
