package com.shiwushop.catalog.dto.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class CategoryCreateDTO {

    @NotBlank(message = "分类名称不能为空")
    private String name;

    @Positive(message = "父分类ID必须为正数")
    private Long parentId;

    @Min(value = 0, message = "排序值不能为负数")
    private Integer sortOrder;

    @Min(value = 0, message = "展示状态只能是0或1")
    @Max(value = 1, message = "展示状态只能是0或1")
    private Integer visible;
}
