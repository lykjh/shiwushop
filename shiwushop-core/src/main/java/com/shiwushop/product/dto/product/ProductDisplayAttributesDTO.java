package com.shiwushop.product.dto.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProductDisplayAttributesDTO(
    /** 当前对象的明细列表。 */
    @NotNull @Size(max = 20) List<@Valid Item> items
) {
    public record Item(
        /** 展示属性名称。 */
        @NotBlank @Size(max = 40) String key,
        /** 展示属性值。 */
        @NotBlank @Size(max = 200) String value,
        /** 展示顺序，范围 0 至 19。 */
        @Min(0) @Max(19) int position
    ) {
    }
}
