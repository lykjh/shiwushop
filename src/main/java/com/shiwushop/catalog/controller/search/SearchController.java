package com.shiwushop.catalog.controller.search;

import com.shiwushop.common.Result;
import com.shiwushop.catalog.search.DiscoveryService;
import com.shiwushop.catalog.search.SearchSuggestions;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {
    private final DiscoveryService discovery;

    /** 根据用户输入的关键词返回搜索建议，供输入框联想使用。 */
    @GetMapping("/suggestions")
    public Result<SearchSuggestions> suggestions(@RequestParam String q) {
        return Result.success(discovery.suggestions(q));
    }
}
