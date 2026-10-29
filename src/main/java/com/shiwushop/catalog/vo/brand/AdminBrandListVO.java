package com.shiwushop.catalog.vo.brand;

import lombok.Data;

@Data
public class AdminBrandListVO {
    private Long id;
    private String name;
    private Long logoMediaId;
    private Integer enabled;
}
