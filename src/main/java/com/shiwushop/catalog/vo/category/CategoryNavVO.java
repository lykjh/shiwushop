package com.shiwushop.catalog.vo.category;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CategoryNavVO {
    private Long id;
    private String name;
    private Integer level;
    private Boolean hasChildren;
    private Integer sortOrder;
}
