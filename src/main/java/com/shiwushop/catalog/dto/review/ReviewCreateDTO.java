package com.shiwushop.catalog.dto.review;

import java.util.List;

public record ReviewCreateDTO(Integer rating, String content, List<Long> imageMediaIds) { }
