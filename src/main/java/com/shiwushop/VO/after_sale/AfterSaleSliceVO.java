package com.shiwushop.vo.after_sale;

import java.util.List;

public record AfterSaleSliceVO(List<AfterSaleVO> items, Long nextBeforeId) {}
