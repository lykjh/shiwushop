package com.shiwushop.product.dto.review;

import java.util.List;

import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;

public record ReviewFollowUpDTO(
    /** 评价正文。 */
    @NotBlank @CodePointLength(max = 2000) String content,
    /** 图片媒体 ID 列表。 */
    @Size(max = 9) List<@NotNull @Positive Long> imageMediaIds
) {
    public ReviewFollowUpDTO {
        if (content != null)
            content = content.strip();
    }
}
