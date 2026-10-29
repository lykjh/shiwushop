package com.shiwushop.search;

import com.shiwushop.catalog.search.SearchGateway;
import com.shiwushop.catalog.search.SearchIndexSource;
import com.shiwushop.catalog.search.SearchOutboxWorker;
import com.shiwushop.common.outbox.OutboxEventMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchOutboxWorkerUnitTests {
    @Test
    void fullFanoutBatchCheckpointsInsteadOfAcknowledgingWholeEvent() {
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        SearchIndexSource source = mock(SearchIndexSource.class);
        SearchGateway search = mock(SearchGateway.class);
        ObjectMapper json = new ObjectMapper();
        OutboxEventMapper.SearchEvent event = new OutboxEventMapper.SearchEvent();
        event.setId(41L);
        event.setEventType("SEARCH_SHOP_REFRESH");
        event.setAggregateType("SHOP");
        event.setAggregateId(7L);
        List<Long> ids = LongStream.rangeClosed(1, 200).boxed().toList();
        when(outbox.selectPendingSearchEvent(any())).thenReturn(event);
        when(outbox.claimSearchEvent(eq(41L), anyString(), any(), any())).thenReturn(1);
        when(source.affectedProductIds("SEARCH_SHOP_REFRESH", 7L, 0L, 200)).thenReturn(ids);
        when(source.lookupDocument("SEARCH_SHOP_REFRESH", 7L)).thenReturn(null);
        when(source.productDocuments(ids)).thenReturn(Map.of());
        StringBuilder bulkResult = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 201; i++) {
            if (i > 0) bulkResult.append(',');
            bulkResult.append("{\"delete\":{\"status\":404}}");
        }
        bulkResult.append("]}");
        when(search.bulk(anyString())).thenReturn(json.readTree(bulkResult.toString()));

        new SearchOutboxWorker(outbox, source, search, json,
                Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC), true).processNext();

        verify(outbox).checkpointSearchEvent(eq(41L), anyString(), eq(200L));
    }
}
