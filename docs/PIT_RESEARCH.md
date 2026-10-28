# S02：搜索 PIT 生命周期参考

查阅日期：2026-10-28。这里只记录接口依据与本项目的选择；实现、测试结果另记在验证日志中。

## 参考实现和接口

- [OpenSearch](https://github.com/opensearch-project/OpenSearch) 是本项目所用搜索引擎的官方仓库，查阅时 GitHub 显示约 13.9k stars。其 [REST API 规范](https://github.com/opensearch-project/OpenSearch/blob/56bfaa9cc8c5ba4ee49a92e3476dd9898b76e6e3/rest-api-spec/src/main/resources/rest-api-spec/api/delete_pit.json) 定义 `DELETE /_search/point_in_time`，请求体必需；[官方文档](https://docs.opensearch.org/latest/api-reference/search-apis/point-in-time-api/) 给出的按 ID 删除请求体是 `{"pit_id":["<id>"]}`。服务端 [响应实现](https://github.com/opensearch-project/OpenSearch/blob/56bfaa9cc8c5ba4ee49a92e3476dd9898b76e6e3/server/src/main/java/org/opensearch/action/search/DeletePitResponse.java) 在成功处理请求时返回顶层 `pits` 数组，各项含 `successful` 和 `pit_id`；没有顶层失败计数，HTTP 状态固定为 200，故仍需检查单项结果。启用安全插件时，调用方需要 PIT 删除权限。
- [elastic/elasticsearch-py](https://github.com/elastic/elasticsearch-py) 是相近搜索客户端，查阅时 GitHub 显示约 4.4k stars。其 [PIT 上下文管理实现](https://github.com/elastic/elasticsearch-py/blob/ba53123a99f31797a5e438e4563dbfc5c3568713/elasticsearch/dsl/_sync/search.py) 在正常使用结束后调用 `close_point_in_time`。本项目用 OpenSearch REST 接口，不引入另一套客户端，也不复制其代码。
- OpenSearch [Search API 文档](https://docs.opensearch.org/latest/api-reference/search/)说明 PIT 搜索的响应包含 `pit_id`；[PIT 文档](https://docs.opensearch.org/latest/search-plugins/searching-data/point-in-time/)说明 PIT 保留索引段，搜索请求的 `keep_alive` 会延长存活时间，超时后自动清理。

## 本项目选择

`DiscoveryService.list` 首批打开 PIT，后续页从签名游标恢复；搜索响应带新的 `pit_id` 时更新当前持有的 ID。还有下一页时将当前 ID 放进游标，交给后续请求继续使用。末页及已知 ID 的异常路径主动调用 `SearchGateway.closePit`；关闭失败只记录，不覆盖原始异常或已经得到的列表结果。用户中途离开页面时服务端收不到结束信号，继续依赖现有 2 分钟 `keep_alive` 到期。

选择“已结束即删除、未结束保留 TTL”，比只等 TTL 更快释放完整浏览的 PIT 资源；比新增会话表或定时清理器改动更小。代价是多一次删除请求，且中途离开的 PIT 仍保留到到期。测试应覆盖末页、异常、仍有下一页、响应更新 PIT ID，以及删除失败不改变原响应；上线后查看 PIT 活跃量和删除失败日志。
