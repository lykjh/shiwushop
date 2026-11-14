package com.shiwushop.merchant.vo.shop;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ShopVO {
    /** 店铺 ID。 */
    private Long id;
    /** 店主用户 ID。 */
    private Long ownerUserId;
    /** 店铺名称。 */
    private String name;
    /** 标识图片的媒体 ID。 */
    private Long logoMediaId;
    /** 展示说明。 */
    private String description;
    /** 是否启用，1 是、0 否。 */
    private Integer enabled;
}
