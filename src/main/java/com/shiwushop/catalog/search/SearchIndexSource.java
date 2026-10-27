package com.shiwushop.catalog.search;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads current public database state when replaying search events. */
@Component
@RequiredArgsConstructor
public class SearchIndexSource {
    private final NamedParameterJdbcTemplate db;

    public List<Long> affectedProductIds(String eventType, long aggregateId, long afterId, int limit) {
        if (eventType.equals("SEARCH_PRODUCT_REFRESH"))
            return afterId == 0 ? List.of(aggregateId) : List.of();
        String sql = switch (eventType) {
            case "SEARCH_SHOP_REFRESH" -> """
                    SELECT id FROM products WHERE shop_id = :id AND id > :afterId
                    ORDER BY id LIMIT :limit
                    """;
            case "SEARCH_BRAND_REFRESH" -> """
                    SELECT p.id FROM products p JOIN product_brands pb ON pb.product_id = p.id
                    WHERE pb.brand_id = :id AND p.id > :afterId ORDER BY p.id LIMIT :limit
                    """;
            case "SEARCH_CATEGORY_REFRESH" -> """
                    WITH RECURSIVE descendants AS (
                        SELECT id FROM categories WHERE id = :id
                        UNION ALL SELECT c.id FROM categories c
                        JOIN descendants parent ON c.parent_id = parent.id
                    )
                    SELECT p.id FROM products p JOIN descendants d ON d.id = p.category_id
                    WHERE p.id > :afterId ORDER BY p.id LIMIT :limit
                    """;
            default -> throw new IllegalArgumentException("Unknown search event: " + eventType);
        };
        return db.queryForList(sql, Map.of("id", aggregateId, "afterId", afterId, "limit", limit), Long.class);
    }

    public Map<String, Object> lookupDocument(String eventType, long id) {
        String table = switch (eventType) {
            case "SEARCH_SHOP_REFRESH" -> "shops";
            case "SEARCH_BRAND_REFRESH" -> "brands";
            default -> throw new IllegalArgumentException("Not a lookup event: " + eventType);
        };
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id, name, enabled FROM " + table + " WHERE id = :id", Map.of("id", id));
        if (rows.isEmpty()) return null;
        Map<String, Object> row = new HashMap<>(rows.getFirst());
        row.put("enabled", ((Number) row.get("enabled")).intValue() != 0);
        return row;
    }

    public Map<Long, Map<String, Object>> productDocuments(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<String, Object> args = Map.of("ids", ids);
        List<Map<String, Object>> products = db.queryForList("""
                SELECT p.id, p.name, p.category_id AS categoryId, pb.brand_id AS brandId,
                       b.name AS brandName, s.id AS shopId, s.name AS shopName,
                       COALESCE(sales.sold_count, 0) AS soldCount, p.status,
                       DATE_FORMAT(p.updated_at, '%Y-%m-%dT%H:%i:%s') AS updatedAt
                FROM products p JOIN shops s ON s.id = p.shop_id AND s.enabled = 1
                LEFT JOIN product_brands pb ON pb.product_id = p.id
                LEFT JOIN brands b ON b.id = pb.brand_id
                LEFT JOIN product_sales_stats sales ON sales.product_id = p.id
                WHERE p.id IN (:ids) AND p.status = 'ON_SALE' ORDER BY p.id
                """, args);
        Map<Long, Map<String, Object>> prices = byProduct(db.queryForList("""
                SELECT product_id AS productId,
                       COALESCE(MIN(CASE WHEN stock > 0 THEN price END), MIN(price)) AS startingPrice,
                       SUM(stock > 0) = 0 AS soldOut
                FROM skus WHERE product_id IN (:ids) GROUP BY product_id
                """, args));
        Map<Long, List<BigDecimal>> available = new HashMap<>();
        for (Map<String, Object> row : db.queryForList("""
                SELECT product_id AS productId, price FROM skus
                WHERE product_id IN (:ids) AND stock > 0 ORDER BY price
                """, args)) {
            long id = ((Number) row.get("productId")).longValue();
            available.computeIfAbsent(id, ignored -> new ArrayList<>()).add((BigDecimal) row.get("price"));
        }
        Map<Long, Map<String, Object>> reviews = byProduct(db.queryForList("""
                SELECT review.product_id AS productId, COUNT(*) AS reviewCount,
                       ROUND(AVG(review.rating), 2) AS averageRating
                FROM product_reviews review JOIN order_items item ON item.id = review.order_item_id
                WHERE review.product_id IN (:ids) AND review.deleted_at IS NULL
                  AND review.hidden_at IS NULL
                  AND item.quantity > (SELECT COALESCE(SUM(refunded.quantity), 0)
                    FROM after_sale_items refunded JOIN after_sale_cases sale ON sale.id = refunded.case_id
                    WHERE refunded.order_item_id = item.id AND sale.status = 'REFUNDED')
                GROUP BY review.product_id
                """, args));
        Map<Long, Category> categories = new HashMap<>();
        db.query("SELECT id, parent_id, visible FROM categories", rs -> {
            Long parentId = rs.getObject("parent_id", Long.class);
            categories.put(rs.getLong("id"), new Category(parentId, rs.getBoolean("visible")));
        });
        Map<Long, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> source : products) {
            long id = ((Number) source.get("id")).longValue();
            List<Long> path = categoryPath(((Number) source.get("categoryId")).longValue(), categories);
            Map<String, Object> price = prices.get(id);
            if (path == null || price == null) continue;
            Map<String, Object> doc = new HashMap<>(source);
            doc.put("categoryPathIds", path);
            doc.put("startingPrice", price.get("startingPrice"));
            doc.put("soldOut", ((Number) price.get("soldOut")).intValue() != 0);
            doc.put("availablePrices", available.getOrDefault(id, List.of()));
            Map<String, Object> review = reviews.get(id);
            doc.put("reviewCount", review == null ? 0 : review.get("reviewCount"));
            doc.put("averageRating", review == null ? 0 : review.get("averageRating"));
            result.put(id, doc);
        }
        return result;
    }

    private Map<Long, Map<String, Object>> byProduct(List<Map<String, Object>> rows) {
        Map<Long, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> row : rows)
            result.put(((Number) row.get("productId")).longValue(), row);
        return result;
    }

    static List<Long> categoryPath(long id, Map<Long, Category> categories) {
        List<Long> path = new ArrayList<>();
        Long current = id;
        while (current != null) {
            Category category = categories.get(current);
            if (category == null || !category.visible() || path.size() == 3) return null;
            path.addFirst(current);
            current = category.parentId();
        }
        return path;
    }

    record Category(Long parentId, boolean visible) {}
}
