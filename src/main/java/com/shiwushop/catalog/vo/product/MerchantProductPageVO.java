package com.shiwushop.catalog.vo.product;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class MerchantProductPageVO {
    private List<MerchantProductVO> items;
    private Integer page;
    private Integer size;
    private Long total;
}
