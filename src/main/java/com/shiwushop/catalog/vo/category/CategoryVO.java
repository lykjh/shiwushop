package com.shiwushop.catalog.vo.category;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CategoryVO {

    private Long id;
    private Long parentId;
    private Integer level;
    private String name;
    private Integer visible;
    private Integer sortOrder;

    public CategoryVO(Long id, String name) {
        this.id = id;
        this.name = name;
    }
}
