package com.shiwushop.aftersale.vo;

import java.util.List;

public record AfterSaleSliceVO(
    /** 当前对象的明细列表。 */
    List<AfterSaleVO> items,
    /** 下一页的向前 ID 游标。 */
    Long nextBeforeId
) {
}
