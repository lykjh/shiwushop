# 运行日志

本地运行时写入 `logs/copy.log`；部署后写入服务器 `~/copy/logs/copy.log`。日志仍同时输出到容器标准输出，可用 `docker logs copy-app-1` 查看。日志文件按 20 MB 滚动，最多保留 14 天，总量上限 1 GB。

HTTP 请求日志只记录方法、路由模板、状态码、耗时和请求 ID；不记录查询参数、请求体、Cookie、用户名或密码。发生问题时可用响应头 `X-Request-Id` 在日志中定位同一次请求。

排查方法调用时，把环境变量 `SHOP_METHOD_LOG_ENABLED` 设为 `true` 并重启应用。方法日志覆盖 `com.copy` 下经 Spring Bean 代理调用的公开方法，记录类名、方法名、成功或异常类型、耗时，并沿用同一请求 ID；不记录参数、返回值或异常消息。排查结束后改回 `false` 并重启。Spring AOP 不拦截私有方法、对象内部自调用和未由 Spring 管理的对象。
