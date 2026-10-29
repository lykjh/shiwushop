package com.shiwushop.catalog.vo.review;

import java.time.LocalDateTime;
import java.util.List;

public record PublicReviewVO(Long id, Integer rating, String content, String followUp,
                             LocalDateTime createdAt, LocalDateTime followUpAt,
                             List<Long> imageMediaIds, List<Long> followUpImageMediaIds) { }
