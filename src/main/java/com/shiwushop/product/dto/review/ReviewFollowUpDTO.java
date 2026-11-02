package com.shiwushop.product.dto.review;

import java.util.List;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;

public record ReviewFollowUpDTO(@NotBlank @CodePointLength(max = 2000) String content,
                                @Size(max = 9) List<@NotNull @Positive Long> imageMediaIds) {
    public ReviewFollowUpDTO { if (content != null) content = content.strip(); }
}
