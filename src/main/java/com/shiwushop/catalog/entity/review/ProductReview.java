package com.shiwushop.catalog.entity.review;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProductReview {
    private Long id;
    private Long orderItemId;
    private Long productId;
    private Long buyerUserId;
    private Integer rating;
    private String content;
    private String followUp;
    private LocalDateTime followUpAt;
    private LocalDateTime deletedAt;
    private LocalDateTime hiddenAt;
    private Boolean countedInSummary;
    private LocalDateTime createdAt;
}
