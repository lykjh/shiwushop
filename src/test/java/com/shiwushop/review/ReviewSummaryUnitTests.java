package com.shiwushop.review;

import com.shiwushop.common.auth.AccountAccess;
import com.shiwushop.common.auth.MediaAssetAccess;
import com.shiwushop.dto.review.ReviewCreateDTO;
import com.shiwushop.entity.review.ProductReview;
import com.shiwushop.mapper.outbox.OutboxEventMapper;
import com.shiwushop.mapper.review.ProductReviewMapper;
import com.shiwushop.service.impl.ReviewServiceImpl;
import com.shiwushop.vo.query.review.ReviewEligibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReviewSummaryUnitTests {
    private final AccountAccess accounts = mock(AccountAccess.class);
    private final ProductReviewMapper reviews = mock(ProductReviewMapper.class);
    private final OutboxEventMapper outbox = mock(OutboxEventMapper.class);
    private final ReviewServiceImpl service = new ReviewServiceImpl(accounts, reviews,
            mock(MediaAssetAccess.class), outbox);

    @Test
    void creatingReviewAddsItsRatingOnce() {
        ReviewEligibility eligible = new ReviewEligibility();
        eligible.setOrderId(2L);
        eligible.setBuyerUserId(3L);
        eligible.setProductId(4L);
        eligible.setOrderStatus("PAID");
        eligible.setShipmentStatus("CONFIRMED");
        eligible.setQuantity(1);
        eligible.setRefundedQuantity(0);
        when(reviews.selectEligibilityForUpdate(5L)).thenReturn(eligible);
        when(reviews.selectIdByOrderItemId(5L)).thenReturn(null);
        when(reviews.insertReview(any())).thenAnswer(invocation -> {
            ProductReview row = invocation.getArgument(0);
            assertEquals(true, row.getCountedInSummary());
            row.setId(6L);
            return 1;
        });
        when(reviews.addToSummary(4L, 4)).thenReturn(1);
        when(outbox.insertSearchProductRefresh(4L)).thenReturn(1);

        assertEquals(6L, service.create(3L, 2L, 5L, new ReviewCreateDTO(4, "评价", List.of())));
        verify(reviews).addToSummary(4L, 4);
    }

    @Test
    void deletingAnAlreadyExcludedReviewDoesNotSubtractTwice() {
        ProductReview review = new ProductReview();
        review.setId(6L);
        review.setBuyerUserId(3L);
        review.setProductId(4L);
        review.setRating(4);
        review.setCountedInSummary(false);
        when(reviews.selectForUpdate(6L)).thenReturn(review);
        when(reviews.softDelete(6L)).thenReturn(1);
        when(outbox.insertSearchProductRefresh(4L)).thenReturn(1);

        service.delete(3L, 6L);
        verify(reviews).softDelete(6L);
        verify(reviews, never()).removeFromSummary(any(), anyInt());
    }

    @Test
    void deletingCountedReviewSubtractsItsRating() {
        ProductReview review = new ProductReview();
        review.setId(6L);
        review.setBuyerUserId(3L);
        review.setProductId(4L);
        review.setRating(4);
        review.setCountedInSummary(true);
        when(reviews.selectForUpdate(6L)).thenReturn(review);
        when(reviews.softDelete(6L)).thenReturn(1);
        when(reviews.removeFromSummary(4L, 4)).thenReturn(1);
        when(outbox.insertSearchProductRefresh(4L)).thenReturn(1);

        service.delete(3L, 6L);
        verify(reviews).removeFromSummary(4L, 4);
    }
}
