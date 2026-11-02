package com.shiwushop.aftersale.vo;

import java.util.List;

public record AfterSaleSliceVO(List<AfterSaleVO> items, Long nextBeforeId) {}
