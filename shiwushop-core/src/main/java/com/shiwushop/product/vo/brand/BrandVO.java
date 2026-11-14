package com.shiwushop.product.vo.brand;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BrandVO {
    /** 品牌 ID。 */
    private Long id;
    /** 品牌名称。 */
    private String name;
    /** 标识图片的媒体 ID。 */
    private Long logoMediaId;
    /** 是否启用，1 是、0 否。 */
    private Integer enabled;
}
