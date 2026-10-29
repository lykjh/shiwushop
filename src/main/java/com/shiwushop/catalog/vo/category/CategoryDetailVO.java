package com.shiwushop.catalog.vo.category;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class CategoryDetailVO {
    private Long id;
    private String name;
    private Integer level;
    private List<CategoryPathItemVO> path;
}
