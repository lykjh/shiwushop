package com.shiwushop.catalog.search;

import com.shiwushop.catalog.vo.product.PublicProductVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.pagination.PageSpec;
import com.shiwushop.catalog.entity.brand.Brand;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.mapper.brand.BrandMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.catalog.vo.query.product.PublicProductRow;
import com.shiwushop.catalog.service.CategoryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class DiscoveryService {
    private final SearchGateway search;
    private final DiscoveryCursor cursors;
    private final ProductMapper products;
    private final BrandMapper brands;
    private final ShopMapper shops;
    private final CategoryService categories;
    private final ObjectMapper json;
    private final boolean enabled;

    public DiscoveryService(SearchGateway search, DiscoveryCursor cursors, ProductMapper products,
                            BrandMapper brands, ShopMapper shops, CategoryService categories,
                            ObjectMapper json,
                            @Value("${shop.search.discovery-enabled:false}") boolean enabled) {
        this.search = search;
        this.cursors = cursors;
        this.products = products;
        this.brands = brands;
        this.shops = shops;
        this.categories = categories;
        this.json = json;
        this.enabled = enabled;
    }

    /** 搜索索引只负责候选和顺序；每批展示前以数据库核对可售状态与当前价格。 */
    public DiscoveryPage list(DiscoveryQuery raw, String cursor, Long userId) {
        if (!enabled) throw new BusinessException(503, "商品发现索引尚未就绪");
        DiscoveryQuery query = normalize(raw);
        validate(query);
        String fingerprint = json.writeValueAsString(query);
        // 游标绑定筛选条件和访问身份，PIT 固定本轮滚动加载的索引视图。
        DiscoveryCursor.State state = cursor == null ? null : cursors.decode(cursor, fingerprint, userId);
        String pit = state == null ? search.openPit() : state.pit();
        boolean keepPit = false;
        try {
            List<Object> position = state == null ? null : state.sort();
            List<PublicProductVO> cards = new ArrayList<>();
            List<Object> lastReturnedSort = null;
            boolean hasMore = false;
            boolean exhausted = false;
            int scanned = 0;
            int fetchSize = Math.min(100, Math.max(20, query.size() * 2));
            while (!exhausted && scanned < 1000) {
                Map<String, Object> body = request(query, pit, position, fetchSize);
                JsonNode result = search.search(body);
                String renewedPit = result.path("pit_id").asText();
                if (!renewedPit.isBlank()) pit = renewedPit;
                JsonNode hits = result.path("hits").path("hits");
                if (!hits.isArray()) throw new BusinessException(503, "搜索响应格式错误");
                List<Long> ids = new ArrayList<>();
                for (JsonNode hit : hits) ids.add(hit.path("_source").path("id").asLong());
                Map<Long, PublicProductRow> current = new HashMap<>();
                if (!ids.isEmpty()) {
                    for (PublicProductRow row : products.selectPublicCardsByIds(ids, query.categoryId(),
                            query.brandId(), query.shopId(), query.minPrice(), query.maxPrice())) {
                        current.put(row.getId(), row);
                    }
                }
                // 已失效的索引命中会被过滤；继续扫描候选以尽量补足这一批。
                for (JsonNode hit : hits) {
                    scanned++;
                    position = json.readValue(hit.path("sort").toString(), List.class);
                    PublicProductRow row = current.get(hit.path("_source").path("id").asLong());
                    if (row == null) continue;
                    if (cards.size() == query.size()) {
                        hasMore = true;
                        break;
                    }
                    cards.add(toCard(row));
                    lastReturnedSort = position;
                }
                if (hasMore) break;
                exhausted = hits.size() < fetchSize;
            }
            if (!hasMore && !exhausted) throw new BusinessException(503, "搜索索引与商品数据差异过大，请稍后重试");
            String next = hasMore ? cursors.encode(new DiscoveryCursor.State(pit, lastReturnedSort,
                    fingerprint, userId, Instant.now().plusSeconds(120).getEpochSecond())) : null;
            keepPit = hasMore;
            return new DiscoveryPage(cards, hasMore, next);
        } finally {
            // 只有下一页游标会继续使用 PIT；异常或末页立即释放最新 ID。
            if (!keepPit) search.closePit(pit);
        }
    }

    /** 已显示的卡片重新核对数据库，供前端回滚到列表时刷新价格和可见状态。 */
    public DiscoveryRefresh refresh(List<Long> ids, DiscoveryQuery raw) {
        if (!enabled) throw new BusinessException(503, "商品发现索引尚未就绪");
        if (ids == null || ids.isEmpty() || ids.size() > 60 || ids.stream().anyMatch(id -> id == null || id <= 0))
            throw new BusinessException(400, "商品ID数量或格式错误");
        DiscoveryQuery query = normalize(raw);
        validate(query);
        List<Long> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        List<Long> matching = unique;
        if (query.q() != null && !query.q().isBlank()) {
            JsonNode hits = search.searchIndex("products", Map.of(
                    "size", unique.size(), "_source", List.of("id"),
                    "query", Map.of("bool", Map.of(
                            "must", List.of(Map.of("multi_match", Map.of("query", query.q(),
                                    "fields", List.of("name^6", "brandName^3", "shopName^3")))),
                            "filter", List.of(Map.of("terms", Map.of("id", unique)))))))
                    .path("hits").path("hits");
            if (!hits.isArray()) throw new BusinessException(503, "搜索响应格式错误");
            List<Long> found = new ArrayList<>();
            for (JsonNode hit : hits) found.add(hit.path("_source").path("id").asLong());
            matching = found;
        }
        Map<Long, PublicProductRow> rows = new HashMap<>();
        if (!matching.isEmpty()) for (PublicProductRow row : products.selectPublicCardsByIds(matching,
                query.categoryId(), query.brandId(), query.shopId(), query.minPrice(), query.maxPrice()))
            rows.put(row.getId(), row);
        return new DiscoveryRefresh(unique.stream().filter(rows::containsKey).map(id -> toCard(rows.get(id))).toList(),
                unique.stream().filter(id -> !rows.containsKey(id)).toList());
    }

    public SearchSuggestions suggestions(String raw) {
        if (!enabled) throw new BusinessException(503, "商品发现索引尚未就绪");
        String q = raw == null ? "" : raw.strip();
        if (q.isEmpty() || q.codePointCount(0, q.length()) > 100)
            throw new BusinessException(400, "搜索词长度必须在1到100个字符之间");
        List<Long> productIds = suggestIds("products", q);
        List<Long> shopIds = suggestIds("shops", q);
        List<Long> brandIds = suggestIds("brands", q);
        Map<Long, PublicProductRow> productRows = new HashMap<>();
        if (!productIds.isEmpty()) for (PublicProductRow row : products.selectPublicCardsByIds(
                productIds, null, null, null, null, null)) productRows.put(row.getId(), row);
        Map<Long, Shop> shopRows = new HashMap<>();
        if (!shopIds.isEmpty()) for (Shop row : shops.selectBatchIds(shopIds)) shopRows.put(row.getId(), row);
        Map<Long, Brand> brandRows = new HashMap<>();
        if (!brandIds.isEmpty()) for (Brand row : brands.selectBatchIds(brandIds)) brandRows.put(row.getId(), row);
        return new SearchSuggestions(
                productIds.stream().filter(productRows::containsKey).map(id ->
                        new SearchSuggestions.Entry(id, productRows.get(id).getName(), "/products/" + id)).toList(),
                shopIds.stream().filter(id -> shopRows.containsKey(id) && Integer.valueOf(1).equals(shopRows.get(id).getEnabled()))
                        .map(id -> new SearchSuggestions.Entry(id, shopRows.get(id).getName(), "/shops/" + id)).toList(),
                brandIds.stream().filter(id -> brandRows.containsKey(id) && Integer.valueOf(1).equals(brandRows.get(id).getEnabled()))
                        .map(id -> new SearchSuggestions.Entry(id, brandRows.get(id).getName(), "/brands/" + id)).toList());
    }

    private List<Long> suggestIds(String index, String q) {
        JsonNode hits = search.searchIndex(index, Map.of("size", 5, "_source", List.of("id"),
                "query", Map.of("bool", Map.of(
                        "must", List.of(Map.of("match", Map.of("name", q))),
                        "should", List.of(Map.of("term", Map.of("name.exact", Map.of(
                                "value", q, "boost", 10)))))))).path("hits").path("hits");
        if (!hits.isArray()) throw new BusinessException(503, "搜索响应格式错误");
        List<Long> ids = new ArrayList<>();
        for (JsonNode hit : hits) ids.add(hit.path("_source").path("id").asLong());
        return ids;
    }

    private DiscoveryQuery normalize(DiscoveryQuery query) {
        return new DiscoveryQuery(query.q() == null ? null : query.q().strip(), query.categoryId(),
                query.brandId(), query.shopId(), query.minPrice(), query.maxPrice(), query.size());
    }

    private void validate(DiscoveryQuery q) {
        PageSpec.of(1, q.size());
        if (q.q() != null && q.q().codePointCount(0, q.q().length()) > 100) {
            throw new BusinessException(400, "搜索词不能超过100个字符");
        }
        if (q.categoryId() != null) categories.detail(positive(q.categoryId()));
        if (q.brandId() != null && brands.selectById(positive(q.brandId())) == null)
            throw new BusinessException(404, "品牌不存在");
        if (q.shopId() != null && shops.selectById(positive(q.shopId())) == null)
            throw new BusinessException(404, "店铺不存在");
        if ((q.minPrice() != null && q.minPrice().signum() < 0)
                || (q.maxPrice() != null && q.maxPrice().signum() < 0)
                || (q.minPrice() != null && q.maxPrice() != null
                    && q.minPrice().compareTo(q.maxPrice()) > 0)) {
            throw new BusinessException(400, "价格区间错误");
        }
    }

    private long positive(Long id) {
        if (id <= 0) throw new BusinessException(400, "筛选ID必须大于0");
        return id;
    }

    private Map<String, Object> request(DiscoveryQuery q, String pit, List<Object> position, int size) {
        List<Object> filters = new ArrayList<>();
        if (q.categoryId() != null) filters.add(Map.of("term", Map.of("categoryPathIds", q.categoryId())));
        if (q.brandId() != null) filters.add(Map.of("term", Map.of("brandId", q.brandId())));
        if (q.shopId() != null) filters.add(Map.of("term", Map.of("shopId", q.shopId())));
        if (q.minPrice() != null || q.maxPrice() != null) {
            Map<String, BigDecimal> price = new LinkedHashMap<>();
            if (q.minPrice() != null) price.put("gte", q.minPrice());
            if (q.maxPrice() != null) price.put("lte", q.maxPrice());
            filters.add(Map.of("range", Map.of("availablePrices", price)));
        }
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", filters);
        boolean hasQuery = q.q() != null && !q.q().isBlank();
        if (hasQuery) {
            bool.put("must", List.of(Map.of("multi_match", Map.of(
                    "query", q.q(), "fields", List.of("name^6", "brandName^3", "shopName^3")))));
            bool.put("should", List.of(Map.of("term", Map.of("name.exact", Map.of(
                    "value", q.q(), "boost", 10)))));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", size);
        body.put("track_total_hits", false);
        body.put("pit", Map.of("id", pit, "keep_alive", "2m"));
        body.put("query", Map.of("bool", bool));
        body.put("sort", hasQuery ? List.of(Map.of("_score", "desc"), Map.of("id", "desc"))
                : List.of(Map.of("id", "desc")));
        if (position != null) body.put("search_after", position);
        return body;
    }

    private PublicProductVO toCard(PublicProductRow row) {
        return new PublicProductVO(row.getId(), row.getName(), row.getCoverMediaId(),
                row.getBrandId() == null ? null : new PublicProductVO.Brand(row.getBrandId(), row.getBrandName()),
                new PublicProductVO.Shop(row.getShopId(), row.getShopName()),
                row.getStartingPrice(), row.getSoldCount(), Boolean.TRUE.equals(row.getSoldOut()));
    }
}
