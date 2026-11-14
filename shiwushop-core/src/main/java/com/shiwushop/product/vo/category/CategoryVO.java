package com.shiwushop.product.vo.category;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CategoryVO {

    /** 分类 ID。 */
    private Long id;
    /** 父分类 ID；根分类为空。 */
    private Long parentId;
    /** 分类层级。 */
    private Integer level;
    /** 分类名称。 */
    private String name;
    /** 是否公开展示，1 是、0 否。 */
    private Integer visible;
    /** 排序值。 */
    private Integer sortOrder;

    public CategoryVO(Long id, String name) {
        this.id = id;
        this.name = name;
    }
}
