package com.shiwushop.product.dto.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class CategoryCreateDTO {

    /** 名称。 */
    @NotBlank(message = "分类名称不能为空")
    private String name;

    /** 父分类 ID；根分类为空。 */
    @Positive(message = "父分类ID必须为正数")
    private Long parentId;

    /** 排序值。 */
    @Min(value = 0, message = "排序值不能为负数")
    private Integer sortOrder;

    /** 是否公开展示，1 是、0 否。 */
    @Min(value = 0, message = "展示状态只能是0或1")
    @Max(value = 1, message = "展示状态只能是0或1")
    private Integer visible;
}
