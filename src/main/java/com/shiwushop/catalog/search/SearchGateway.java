package com.shiwushop.catalog.search;

import com.shiwushop.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class SearchGateway {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final String baseUrl;

    public SearchGateway(ObjectMapper json,
                         @Value("${shop.search.url:http://127.0.0.1:19200}") String baseUrl) {
        this.json = json;
        this.baseUrl = baseUrl;
    }

    public String openPit() {
        JsonNode result = call("/shiwushop-products-read/_search/point_in_time?keep_alive=2m", null);
        String id = result.path("pit_id").asText();
        if (id.isBlank()) throw new BusinessException(503, "搜索快照创建失败");
        return id;
    }

    public JsonNode search(Map<String, Object> body) {
        return call("/_search", body);
    }

    public JsonNode searchIndex(String index, Map<String, Object> body) {
        return call("/shiwushop-" + index + "-read/_search", body);
    }

    public JsonNode bulk(String ndjson) {
        return send("/_bulk?refresh=wait_for", ndjson, "application/x-ndjson");
    }

    private JsonNode call(String path, Object body) {
        return send(path, body == null ? "" : json.writeValueAsString(body), "application/json");
    }

    private JsonNode send(String path, String payload, String contentType) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // PIT 被 OpenSearch 清理时让前端重载首批，不能继续沿用旧排序位置。
            if (response.statusCode() == 404 && path.equals("/_search")) {
                throw new BusinessException(410, "列表游标已过期，请重新加载");
            }
            if (response.statusCode() / 100 != 2) {
                throw new BusinessException(503, "搜索服务暂不可用");
            }
            return json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "搜索请求中断", e);
        } catch (IOException e) {
            throw new BusinessException(503, "搜索服务暂不可用", e);
        }
    }
}
