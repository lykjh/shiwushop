package com.shiwushop.product.vo.category;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class CategoryDetailVO {
    /** 当前记录 ID。 */
    private Long id;
    /** 名称。 */
    private String name;
    /** 分类层级。 */
    private Integer level;
    /** 展示路径。 */
    private List<CategoryPathItemVO> path;
}
