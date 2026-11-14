package com.shiwushop.merchant.vo.shop;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 店铺公开经营分类；字段与原店铺详情保持一致。 */
@Data
@AllArgsConstructor
public class ShopCategoryVO {
    /** 当前记录 ID。 */
    private Long id;
    /** 名称。 */
    private String name;
}
