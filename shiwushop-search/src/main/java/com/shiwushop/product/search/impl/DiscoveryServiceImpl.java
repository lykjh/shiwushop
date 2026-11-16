package com.shiwushop.product.search.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.pagination.PageSpec;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.product.mapper.brand.BrandMapper;
import com.shiwushop.product.mapper.product.ProductMapper;
import com.shiwushop.product.mapper.product.ProductMapper.DiscoveryFilter;
import com.shiwushop.product.search.DiscoveryCursor;
import com.shiwushop.product.search.DiscoveryPage;
import com.shiwushop.product.search.DiscoveryQuery;
import com.shiwushop.product.search.DiscoveryRefresh;
import com.shiwushop.product.search.DiscoveryService;
import com.shiwushop.product.search.SearchSuggestions;
import com.shiwushop.product.service.CategoryService;
import com.shiwushop.product.vo.product.PublicProductVO;
import com.shiwushop.product.vo.query.product.PublicProductRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DiscoveryServiceImpl implements DiscoveryService {
    private final ProductMapper products;
    private final BrandMapper brands;
    private final ShopService shops;
    private final CategoryService categories;
    private final DiscoveryCursor cursors;
    private final ObjectMapper json;

    @Override
    public DiscoveryPage list(DiscoveryQuery raw, String cursor, Long userId) {
        DiscoveryFilter query = filter(raw);
        String fingerprint = json.writeValueAsString(List.of(query, raw.size()));
        DiscoveryCursor.State position = cursor == null ? null : cursors.decode(cursor, fingerprint, userId);
        List<PublicProductRow> rows = products.selectDiscoveryCards(query, null, false,
                position == null ? null : position.rank(), position == null ? null : position.afterId(),
                raw.size() + 1);
        boolean hasMore = rows.size() > raw.size();
        var visible = rows.stream().limit(raw.size()).toList();
        String next = null;
        if (hasMore) {
            var last = visible.getLast();
            next = cursors.encode(new DiscoveryCursor.State(last.getDiscoveryRank(), last.getId(),
                    fingerprint, userId, Instant.now().plusSeconds(120).getEpochSecond()));
        }
        return new DiscoveryPage(visible.stream().map(this::toCard).toList(), hasMore, next);
    }

    @Override
    public DiscoveryRefresh refresh(List<Long> ids, DiscoveryQuery raw) {
        if (ids == null || ids.isEmpty() || ids.size() > 60 || ids.stream().anyMatch(id -> id == null || id <= 0))
            throw new BusinessException(400, "商品ID数量或格式错误");
        DiscoveryFilter query = filter(raw);
        var unique = List.copyOf(new LinkedHashSet<>(ids));
        Map<Long, PublicProductRow> matches = new HashMap<>();
        for (var row : products.selectDiscoveryCards(query, unique, false, null, null, unique.size()))
            matches.put(row.getId(), row);
        return new DiscoveryRefresh(unique.stream().filter(matches::containsKey).map(id -> toCard(matches.get(id))).toList(),
                unique.stream().filter(id -> !matches.containsKey(id)).toList());
    }

    @Override
    public SearchSuggestions suggestions(String raw) {
        String keyword = keyword(raw);
        if (keyword == null)
            throw new BusinessException(400, "搜索词长度必须在1到100个字符之间");
        var query = new DiscoveryFilter(keyword, singleCharacterPattern(keyword), null, null, null, null, null);
        return new SearchSuggestions(
                products.selectDiscoveryCards(query, null, true, null, null, 5).stream()
                        .map(row -> new SearchSuggestions.Entry(row.getId(), row.getName(), "/products/" + row.getId())).toList(),
                products.suggestDiscoveryShops(query).stream()
                        .map(row -> new SearchSuggestions.Entry(row.id(), row.name(), "/shops/" + row.id())).toList(),
                products.suggestDiscoveryBrands(query).stream()
                        .map(row -> new SearchSuggestions.Entry(row.id(), row.name(), "/brands/" + row.id())).toList());
    }

    private DiscoveryFilter filter(DiscoveryQuery query) {
        PageSpec.of(1, query.size());
        String keyword = keyword(query.q());
        if (query.categoryId() != null)
            categories.detail(positive(query.categoryId()));
        if (query.brandId() != null && brands.selectById(positive(query.brandId())) == null)
            throw new BusinessException(404, "品牌不存在");
        if (query.shopId() != null && shops.findById(positive(query.shopId())) == null)
            throw new BusinessException(404, "店铺不存在");
        if ((query.minPrice() != null && query.minPrice().signum() < 0)
                || (query.maxPrice() != null && query.maxPrice().signum() < 0)
                || (query.minPrice() != null && query.maxPrice() != null
                && query.minPrice().compareTo(query.maxPrice()) > 0))
            throw new BusinessException(400, "价格区间错误");
        return new DiscoveryFilter(keyword, singleCharacterPattern(keyword), query.categoryId(), query.brandId(),
                query.shopId(), query.minPrice(), query.maxPrice());
    }

    private static String keyword(String raw) {
        String keyword = raw == null ? "" : raw.strip();
        if (keyword.codePointCount(0, keyword.length()) > 100)
            throw new BusinessException(400, "搜索词不能超过100个字符");
        return keyword.isEmpty() ? null : keyword;
    }

    /** MySQL 默认按两个字符分词，单字搜索按原字符匹配。 */
    private static String singleCharacterPattern(String keyword) {
        if (keyword == null || keyword.codePointCount(0, keyword.length()) != 1)
            return null;
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static long positive(Long id) {
        if (id <= 0)
            throw new BusinessException(400, "筛选ID必须大于0");
        return id;
    }

    private PublicProductVO toCard(PublicProductRow row) {
        return new PublicProductVO(row.getId(), row.getName(), row.getCoverMediaId(),
                row.getBrandId() == null ? null : new PublicProductVO.Brand(row.getBrandId(), row.getBrandName()),
                new PublicProductVO.Shop(row.getShopId(), row.getShopName()),
                row.getStartingPrice(), row.getSoldCount(), Boolean.TRUE.equals(row.getSoldOut()));
    }
}
