package com.shiwushop.catalog.search;

import java.util.List;

public record SearchSuggestions(List<Entry> products, List<Entry> shops, List<Entry> brands) {
    public record Entry(Long id, String name, String path) { }
}
