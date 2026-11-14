package com.shiwushop.merchant.vo.shop;

import com.shiwushop.merchant.vo.shop.ShopCategoryVO;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ShopDetailVO {
    /** 当前记录 ID。 */
    private Long id;
    /** 名称。 */
    private String name;
    /** 标识图片的媒体 ID。 */
    private Long logoMediaId;
    /** 展示说明。 */
    private String description;
    /** 是否启用，1 是、0 否。 */
    private Integer enabled;
    /** 店铺经营分类列表。 */
    private List<ShopCategoryVO> categories;
}
