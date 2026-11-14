package com.shiwushop.product.dto.category;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class CategoryUpdateDTO {

    /** 名称。 */
    @NotBlank(message = "分类名称不能为空")
    private String name;

    /** 父分类 ID；根分类为空。 */
    @Positive(message = "父分类ID必须为正数")
    private Long parentId;

    /** 是否公开展示，1 是、0 否。 */
    @NotNull(message = "展示状态不能为空")
    @Min(value = 0, message = "展示状态只能是0或1")
    @Max(value = 1, message = "展示状态只能是0或1")
    private Integer visible;

    /** 排序值。 */
    @NotNull(message = "排序值不能为空")
    @Min(value = 0, message = "排序值不能为负数")
    private Integer sortOrder;
}
