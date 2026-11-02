package com.shiwushop.merchant.vo.shop;

import com.shiwushop.merchant.vo.shop.ShopCategoryVO;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ShopDetailVO {
    private Long id;
    private String name;
    private Long logoMediaId;
    private String description;
    private Integer enabled;
    private List<ShopCategoryVO> categories;
}
