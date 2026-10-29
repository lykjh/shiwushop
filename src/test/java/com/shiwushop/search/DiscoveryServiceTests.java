package com.shiwushop.search;

import com.shiwushop.catalog.search.*;
import com.shiwushop.common.BusinessException;
import com.shiwushop.catalog.mapper.brand.BrandMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.catalog.vo.query.product.PublicProductRow;
import com.shiwushop.catalog.service.CategoryService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class DiscoveryServiceTests {
    private final ObjectMapper json = new ObjectMapper();
    private final SearchGateway search = mock(SearchGateway.class);
    private final ProductMapper products = mock(ProductMapper.class);
    private final DiscoveryService service = new DiscoveryService(search, new DiscoveryCursor(json, "test-cursor-secret-with-32-characters"), products,
            mock(BrandMapper.class), mock(ShopMapper.class), mock(CategoryService.class), json, true);

    @Test
    void skipsStaleCandidatesWithoutSkippingNextValidProduct() {
        when(search.openPit()).thenReturn("pit");
        when(search.search(org.mockito.ArgumentMatchers.anyMap())).thenAnswer(invocation -> {
            Map<?, ?> body = invocation.getArgument(0);
            Object after = body.get("search_after");
            List<Long> ids = new ArrayList<>();
            if (after == null) for (long id = 20; id >= 1; id--) ids.add(id);
            else if (after.equals(List.of(1))) ids.add(0L);
            else if (after.equals(List.of(20))) for (long id = 19; id >= 0; id--) ids.add(id);
            List<Map<String, Object>> hits = ids.stream()
                    .map(id -> Map.<String, Object>of("_source", Map.of("id", id), "sort", List.of(id)))
                    .toList();
            return json.readTree(json.writeValueAsString(Map.of("hits", Map.of("hits", hits))));
        });
        when(products.selectPublicCardsByIds(anyList(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenAnswer(invocation -> ((List<Long>) invocation.getArgument(0)).stream()
                        .filter(id -> id == 20 || id == 0).map(this::row).toList());
        DiscoveryQuery query = new DiscoveryQuery(null, null, null, null, null, null, 1);
        DiscoveryPage first = service.list(query, null, null);
        assertEquals(List.of(20L), first.items().stream().map(item -> item.id()).toList());
        assertTrue(first.hasMore());
        verify(search, never()).closePit(anyString());
        DiscoveryPage second = service.list(query, first.nextCursor(), null);
        assertEquals(List.of(0L), second.items().stream().map(item -> item.id()).toList());
        assertFalse(second.hasMore());
        verify(search).closePit("pit");
    }

    @Test
    void closesRenewedPitAfterLastPage() {
        when(search.openPit()).thenReturn("opened-pit");
        when(search.search(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(json.readTree("{\"pit_id\":\"renewed-pit\",\"hits\":{\"hits\":[]}}"));

        DiscoveryPage page = service.list(new DiscoveryQuery(null, null, null, null, null, null, 20), null, null);

        assertFalse(page.hasMore());
        verify(search).closePit("renewed-pit");
    }

    @Test
    void closesLatestPitWhenSearchResponseIsInvalid() {
        when(search.openPit()).thenReturn("opened-pit");
        when(search.search(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(json.readTree("{\"pit_id\":\"renewed-pit\",\"hits\":{}}"));

        assertEquals(503, assertThrows(BusinessException.class, () -> service.list(
                new DiscoveryQuery(null, null, null, null, null, null, 20), null, null)).getCode());
        verify(search).closePit("renewed-pit");
    }

    @Test
    void disabledDiscoveryDoesNotServePilotIndex() {
        var disabled = new DiscoveryService(search, new DiscoveryCursor(json, "test-cursor-secret-with-32-characters"), products,
                mock(BrandMapper.class), mock(ShopMapper.class), mock(CategoryService.class), json, false);
        assertEquals(503, assertThrows(BusinessException.class,
                () -> disabled.list(new DiscoveryQuery(null, null, null, null, null, null, 20), null, null))
                .getCode());
    }

    @Test
    void refreshKeepsOnlyCurrentlyVisibleCards() {
        when(products.selectPublicCardsByIds(anyList(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(row(20L)));
        DiscoveryRefresh result = service.refresh(List.of(20L, 9L),
                new DiscoveryQuery(null, null, null, null, null, null, 20));
        assertEquals(List.of(20L), result.items().stream().map(item -> item.id()).toList());
        assertEquals(List.of(9L), result.invalidIds());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.refresh(List.of(0L),
                new DiscoveryQuery(null, null, null, null, null, null, 20))).getCode());
    }

    @Test
    void refreshRechecksSearchTermsAfterProductRename() {
        when(search.searchIndex(org.mockito.ArgumentMatchers.eq("products"),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(json.readTree("{\"hits\":{\"hits\":[{\"_source\":{\"id\":20}}]}}"));
        when(products.selectPublicCardsByIds(anyList(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(row(20L)));
        DiscoveryRefresh result = service.refresh(List.of(20L, 9L),
                new DiscoveryQuery("商品", null, null, null, null, null, 20));
        assertEquals(List.of(20L), result.items().stream().map(item -> item.id()).toList());
        assertEquals(List.of(9L), result.invalidIds());
        org.mockito.ArgumentCaptor<Map> body = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(search).searchIndex(org.mockito.ArgumentMatchers.eq("products"), body.capture());
        assertTrue(body.getValue().toString().contains("商品"));
        assertTrue(body.getValue().toString().contains("[20, 9]"));
    }

    @Test
    void suggestionsKeepOnlyCurrentProducts() {
        when(search.searchIndex(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenAnswer(invocation -> {
                    String index = invocation.getArgument(0);
                    long id = index.equals("products") ? 20 : 9;
                    return json.readTree(json.writeValueAsString(Map.of("hits", Map.of("hits",
                            List.of(Map.of("_source", Map.of("id", id)))))));
                });
        when(products.selectPublicCardsByIds(anyList(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(row(20L)));
        SearchSuggestions result = service.suggestions("商品");
        assertEquals(List.of(20L), result.products().stream().map(SearchSuggestions.Entry::id).toList());
        assertEquals(List.of(), result.shops());
        assertEquals(List.of(), result.brands());
    }

    @Test
    void priceFilterUsesAnyAvailableSkuPrice() {
        when(search.openPit()).thenReturn("pit");
        when(search.search(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(json.readTree("{\"hits\":{\"hits\":[]}}"));
        service.list(new DiscoveryQuery(null, null, null, null,
                new BigDecimal("25"), new BigDecimal("40"), 20), null, null);
        org.mockito.ArgumentCaptor<Map> body = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(search).search(body.capture());
        assertTrue(body.getValue().toString().contains("availablePrices"));
        assertTrue(body.getValue().toString().contains("25"));
    }

    private PublicProductRow row(Long id) {
        PublicProductRow row = new PublicProductRow();
        row.setId(id);
        row.setName("商品" + id);
        row.setShopId(1L);
        row.setShopName("店铺");
        row.setStartingPrice(BigDecimal.ONE);
        row.setSoldCount(0L);
        row.setSoldOut(false);
        return row;
    }
}
