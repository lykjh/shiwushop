package com.shiwushop.merchant.vo.shop;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ShopPageVO {
    /** 当前对象的明细列表。 */
    private List<ShopPublicVO> items;
    /** 页码，从 1 开始。 */
    private Integer page;
    /** 每页请求条数。 */
    private Integer size;
    /** 符合查询条件的总记录数。 */
    private Long total;
}
