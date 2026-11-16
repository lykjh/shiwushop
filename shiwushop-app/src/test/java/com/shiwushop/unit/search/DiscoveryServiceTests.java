package com.shiwushop.unit.search;

import com.shiwushop.common.BusinessException;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.product.mapper.brand.BrandMapper;
import com.shiwushop.product.mapper.product.ProductMapper;
import com.shiwushop.product.mapper.product.ProductMapper.DiscoveryFilter;
import com.shiwushop.product.mapper.product.ProductMapper.DiscoverySuggestion;
import com.shiwushop.product.search.DiscoveryCursor;
import com.shiwushop.product.search.DiscoveryQuery;
import com.shiwushop.product.search.impl.DiscoveryServiceImpl;
import com.shiwushop.product.service.CategoryService;
import com.shiwushop.product.vo.product.PublicProductVO;
import com.shiwushop.product.vo.query.product.PublicProductRow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscoveryServiceTests {
    private final ObjectMapper json = new ObjectMapper();
    private final ProductMapper products = mock(ProductMapper.class);
    private final DiscoveryServiceImpl service = new DiscoveryServiceImpl(products, mock(BrandMapper.class),
            mock(ShopService.class), mock(CategoryService.class),
            new DiscoveryCursor(json, "test-cursor-secret-with-32-characters"), json);

    @Test
    void cursorUsesLastVisibleRankAndIdAndStaysBoundToPageSize() {
        var filter = new DiscoveryFilter("茶花", null, null, null, null, null, null);
        when(products.selectDiscoveryCards(filter, null, false, null, null, 2))
                .thenReturn(List.of(row(5L, 4), row(9L, 3)));
        when(products.selectDiscoveryCards(filter, null, false, 4, 5L, 2))
                .thenReturn(List.of(row(9L, 3)));

        var first = service.list(query(" 茶花 ", 1), null, 7L);
        assertEquals(List.of(5L), first.items().stream().map(PublicProductVO::id).toList());
        assertTrue(first.hasMore());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.list(query("茶花", 2), first.nextCursor(), 7L)).getCode());

        var second = service.list(query("茶花", 1), first.nextCursor(), 7L);
        assertEquals(List.of(9L), second.items().stream().map(PublicProductVO::id).toList());
        assertFalse(second.hasMore());
        assertNull(second.nextCursor());
    }

    @Test
    void refreshPreservesRequestedOrderAndReportsMissingIdsOnce() {
        var filter = new DiscoveryFilter(null, null, null, null, null, null, null);
        when(products.selectDiscoveryCards(filter, List.of(5L, 8L, 9L), false, null, null, 3))
                .thenReturn(List.of(row(9L, 0), row(5L, 0)));

        var refreshed = service.refresh(List.of(5L, 8L, 9L, 8L), query(null, 20));
        assertEquals(List.of(5L, 9L), refreshed.items().stream().map(PublicProductVO::id).toList());
        assertEquals(List.of(8L), refreshed.invalidIds());
    }

    @Test
    void singleCharacterSuggestionsTreatWildcardsAsLiteral() {
        var filter = new DiscoveryFilter("%", "%!%%", null, null, null, null, null);
        when(products.selectDiscoveryCards(filter, null, true, null, null, 5)).thenReturn(List.of(row(5L, 3)));
        when(products.suggestDiscoveryShops(filter)).thenReturn(List.of(new DiscoverySuggestion(2L, "店铺%")));
        when(products.suggestDiscoveryBrands(filter)).thenReturn(List.of(new DiscoverySuggestion(8L, "品牌%")));

        var result = service.suggestions("%");
        assertEquals("/products/5", result.products().getFirst().path());
        assertEquals("/shops/2", result.shops().getFirst().path());
        assertEquals("/brands/8", result.brands().getFirst().path());
    }

    private static DiscoveryQuery query(String keyword, int size) {
        return new DiscoveryQuery(keyword, null, null, null, null, null, size);
    }

    private static PublicProductRow row(long id, int rank) {
        var row = new PublicProductRow();
        row.setId(id);
        row.setName("茶花");
        row.setShopId(2L);
        row.setSoldCount(0L);
        row.setDiscoveryRank(rank);
        return row;
    }
}
