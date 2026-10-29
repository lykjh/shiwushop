package com.shiwushop.catalog.controller.search;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.catalog.search.DiscoveryPage;
import com.shiwushop.catalog.search.DiscoveryQuery;
import com.shiwushop.catalog.search.DiscoveryService;
import com.shiwushop.catalog.search.DiscoveryRefresh;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/discovery/products")
@RequiredArgsConstructor
public class DiscoveryController {
    private final DiscoveryService discovery;

    @GetMapping
    public Result<DiscoveryPage> list(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) Long categoryId,
                                      @RequestParam(required = false) Long brandId,
                                      @RequestParam(required = false) Long shopId,
                                      @RequestParam(required = false) BigDecimal minPrice,
                                      @RequestParam(required = false) BigDecimal maxPrice,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(required = false) String cursor,
                                      HttpServletRequest request) {
        if (request.getParameterMap().containsKey("page")
                || request.getParameterMap().containsKey("afterId")) {
            throw new BusinessException(400, "商品发现接口只支持不透明游标");
        }
        return Result.success(discovery.list(new DiscoveryQuery(q, categoryId, brandId, shopId,
                minPrice, maxPrice, size), cursor, SessionUser.currentId(request)));
    }

    @GetMapping("/refresh")
    public Result<DiscoveryRefresh> refresh(@RequestParam List<Long> ids,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) Long categoryId,
                                            @RequestParam(required = false) Long brandId,
                                            @RequestParam(required = false) Long shopId,
                                            @RequestParam(required = false) BigDecimal minPrice,
                                            @RequestParam(required = false) BigDecimal maxPrice) {
        return Result.success(discovery.refresh(ids, new DiscoveryQuery(q, categoryId, brandId, shopId,
                minPrice, maxPrice, 20)));
    }
}
