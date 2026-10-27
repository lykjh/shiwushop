package com.shiwushop.review;

import com.shiwushop.controller.review.ProductReviewController;
import com.shiwushop.service.ReviewService;
import com.shiwushop.vo.review.ReviewSummaryVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReviewSummaryControllerSliceTests {
    private final ReviewService reviews = mock(ReviewService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProductReviewController(reviews)).build();

    @Test
    void summaryEndpointKeepsItsPublicResponseShape() throws Exception {
        when(reviews.summary(9L)).thenReturn(new ReviewSummaryVO(2, new BigDecimal("4.50")));

        mvc.perform(get("/products/9/review-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewCount").value(2))
                .andExpect(jsonPath("$.data.averageRating").value(4.50));
    }
}
