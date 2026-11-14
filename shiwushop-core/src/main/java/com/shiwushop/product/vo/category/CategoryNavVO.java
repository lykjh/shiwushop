package com.shiwushop.product.vo.category;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CategoryNavVO {
    /** 当前记录 ID。 */
    private Long id;
    /** 名称。 */
    private String name;
    /** 分类层级。 */
    private Integer level;
    /** 是否包含子分类。 */
    private Boolean hasChildren;
    /** 排序值。 */
    private Integer sortOrder;
}
