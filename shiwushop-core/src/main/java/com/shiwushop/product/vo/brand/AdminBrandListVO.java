package com.shiwushop.product.vo.brand;

import lombok.Data;

@Data
public class AdminBrandListVO {
    /** 当前记录 ID。 */
    private Long id;
    /** 名称。 */
    private String name;
    /** 标识图片的媒体 ID。 */
    private Long logoMediaId;
    /** 是否启用，1 是、0 否。 */
    private Integer enabled;
}
