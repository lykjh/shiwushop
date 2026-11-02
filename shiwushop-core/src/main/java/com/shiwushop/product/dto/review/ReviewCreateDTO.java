package com.shiwushop.product.dto.review;

import java.util.List;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;

public record ReviewCreateDTO(@NotNull @Min(1) @Max(5) Integer rating,
                              @NotBlank @CodePointLength(max = 2000) String content,
                              @Size(max = 9) List<@NotNull @Positive Long> imageMediaIds) {
    // 先规范化再校验，保留原有按去除首尾空白后内容计算长度的行为。
    public ReviewCreateDTO { if (content != null) content = content.strip(); }
}
