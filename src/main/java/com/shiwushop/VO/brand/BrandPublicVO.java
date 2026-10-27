package com.shiwushop.vo.brand;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BrandPublicVO {
    private Long id;
    private String name;
    private Long logoMediaId;
}
