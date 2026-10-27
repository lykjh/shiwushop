package com.shiwushop.vo.shop;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ShopVO {
    private Long id;
    private Long ownerUserId;
    private String name;
    private Long logoMediaId;
    private String description;
    private Integer enabled;
}
