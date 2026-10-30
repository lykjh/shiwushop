package com.shiwushop.catalog.search;

import com.shiwushop.common.outbox.OutboxEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class SearchOutboxWorker {
    private static final int BATCH = 200;
    private final OutboxEventMapper outbox;
    private final SearchIndexSource source;
    private final SearchGateway search;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean enabled;

    /** 注入搜索事件、数据库当前状态和搜索网关；开关决定定时消费者是否执行。 */
    public SearchOutboxWorker(OutboxEventMapper outbox, SearchIndexSource source,
                             SearchGateway search, ObjectMapper json, Clock clock,
                             @Value("${shop.search.worker-enabled:false}") boolean enabled) {
        this.outbox = outbox;
        this.source = source;
        this.search = search;
        this.json = json;
        this.clock = clock;
        this.enabled = enabled;
    }

    /**
     * 定时领取一条待处理搜索事件。数据库租约避免多实例重复推进同一事件；
     * 失败时退避重试，达到上限后标记为死信供运维排查。
     */
    @Scheduled(initialDelayString = "${shop.search.worker-scan-delay-ms:2000}",
            fixedDelayString = "${shop.search.worker-scan-delay-ms:2000}", scheduler = "searchTaskScheduler")
    public void processNext() {
        if (!enabled) return;
        LocalDateTime now = LocalDateTime.now(clock);
        OutboxEventMapper.SearchEvent event = outbox.selectPendingSearchEvent(now);
        if (event == null) return;
        String token = UUID.randomUUID().toString();
        // Search bulk is idempotent by document ID; the DB lease prevents concurrent fanout checkpoints.
        if (outbox.claimSearchEvent(event.getId(), token, now, now.plusSeconds(60)) != 1) return;
        try {
            process(event, token);
        } catch (Exception failure) {
            log.warn("搜索索引事件处理失败，eventId={}", event.getId(), failure);
            if (event.getAttempts() >= 9) outbox.markDead(event.getId(), token);
            else {
                long delay = Math.min(300, 1L << Math.min(event.getAttempts() + 1, 8));
                outbox.markRetry(event.getId(), token, LocalDateTime.now(clock).plusSeconds(delay));
            }
        }
    }

    /**
     * 校验事件类型，分批找受影响商品并从数据库生成最新文档。
     * 写入搜索索引后再保存游标检查点或标记完成，保证失败后可以重试。
     */
    private void process(OutboxEventMapper.SearchEvent event, String token) {
        String type = event.getEventType();
        String expectedAggregate = switch (type) {
            case "SEARCH_PRODUCT_REFRESH" -> "PRODUCT";
            case "SEARCH_SHOP_REFRESH" -> "SHOP";
            case "SEARCH_BRAND_REFRESH" -> "BRAND";
            case "SEARCH_CATEGORY_REFRESH" -> "CATEGORY";
            default -> throw new IllegalArgumentException("Unknown search event: " + type);
        };
        if (!expectedAggregate.equals(event.getAggregateType()))
            throw new IllegalStateException("Search event aggregate type does not match");
        long afterId = event.getLastProductId() == null ? 0 : event.getLastProductId();
        List<Long> ids = source.affectedProductIds(type, event.getAggregateId(), afterId, BATCH);
        List<Action> actions = new ArrayList<>();
        if (afterId == 0 && (type.equals("SEARCH_SHOP_REFRESH") || type.equals("SEARCH_BRAND_REFRESH"))) {
            String index = type.equals("SEARCH_SHOP_REFRESH") ? "shiwushop-shops-v1" : "shiwushop-brands-v1";
            actions.add(new Action(index, event.getAggregateId(),
                    source.lookupDocument(type, event.getAggregateId())));
        }
        Map<Long, Map<String, Object>> documents = source.productDocuments(ids);
        for (long id : ids) actions.add(new Action("shiwushop-products-v2", id, documents.get(id)));
        write(actions);
        if (ids.size() == BATCH)
            outbox.checkpointSearchEvent(event.getId(), token, ids.getLast());
        else outbox.markPublished(event.getId(), token, LocalDateTime.now(clock));
    }

    /** 把文档变化编码成批量索引请求，逐项检查搜索服务的写入结果。 */
    private void write(List<Action> actions) {
        if (actions.isEmpty()) return;
        StringBuilder ndjson = new StringBuilder();
        for (Action action : actions) {
            String operation = action.document() == null ? "delete" : "index";
            ndjson.append(json.writeValueAsString(Map.of(operation,
                    Map.of("_index", action.index(), "_id", action.id())))).append('\n');
            if (action.document() != null)
                ndjson.append(json.writeValueAsString(action.document())).append('\n');
        }
        JsonNode items = search.bulk(ndjson.toString()).path("items");
        if (!items.isArray() || items.size() != actions.size())
            throw new IllegalStateException("Search bulk returned an unexpected item count");
        for (int i = 0; i < actions.size(); i++) {
            String operation = actions.get(i).document() == null ? "delete" : "index";
            int status = items.get(i).path(operation).path("status").asInt();
            if (status != 200 && status != (operation.equals("index") ? 201 : 404))
                throw new IllegalStateException("Search bulk item failed, status=" + status);
        }
    }

    /** 定时记录待处理、死信和最老事件积压时长，供搜索索引运维监控。 */
    @Scheduled(initialDelay = 60000, fixedDelay = 60000, scheduler = "searchTaskScheduler")
    public void reportBacklog() {
        OutboxEventMapper.SearchBacklog backlog = outbox.selectSearchBacklog();
        log.info("搜索事件积压 pending={} dead={} oldestPendingSeconds={}",
                backlog.getPending(), backlog.getDead(), backlog.getOldestPendingSeconds());
    }

    /** 一项批量搜索写入；document 为空表示从索引删除该 ID。 */
    private record Action(String index, long id, Map<String, Object> document) {}
}
