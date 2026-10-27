package com.shiwushop.vo.shop;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ShopPublicVO {
    private Long id;
    private String name;
    private Long logoMediaId;
    private String description;
}
