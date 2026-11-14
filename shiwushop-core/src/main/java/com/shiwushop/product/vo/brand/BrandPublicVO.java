package com.shiwushop.product.vo.brand;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BrandPublicVO {
    /** 当前记录 ID。 */
    private Long id;
    /** 名称。 */
    private String name;
    /** 标识图片的媒体 ID。 */
    private Long logoMediaId;
}
