package com.shiwushop.commerce.dto.after_sale;

import java.util.List;

public record AfterSaleCreateDTO(String type, String reason, List<Item> items) {
    public record Item(Long orderItemId, Integer quantity) {}
}
