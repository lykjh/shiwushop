package com.shiwushop.vo.shop;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ShopPageVO {
    private List<ShopPublicVO> items;
    private Integer page;
    private Integer size;
    private Long total;
}
