package com.shiwushop.commerce.vo.after_sale;

import java.util.List;

public record AfterSaleSliceVO(List<AfterSaleVO> items, Long nextBeforeId) {}
