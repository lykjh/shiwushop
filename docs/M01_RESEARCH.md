# M01 图片读取与缓存

## 现象与原因

原 `MediaServiceImpl.read` 在完成数据库可见性检查后调用 `Files.readAllBytes`。每个请求都会分配整张图片的字节数组；`MediaController` 对公开图片也返回 `no-store`，浏览器无法复用已下载的内容。

## 参考与选择

- [Spring MVC ResponseEntity 文档](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/responseentity.html)：`ResponseEntity<Resource>` 由框架把文件内容写入响应流，资源在写出时再打开。项目沿用该标准能力，不引入额外文件服务。
- [Spring MVC HTTP 缓存文档](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-caching.html)：可用 ETag 处理条件请求。服务先核对图片当前可见性，再让框架返回 304。
- [MDN Cache-Control 文档](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cache-Control)：`no-cache` 允许存储，但每次复用前必须向源站重新验证；`no-store` 禁止存储。公开与私有图片分别使用这两种策略。
- [Halo 项目资源缓存配置](https://github.com/halo-dev/halo/blob/main/application/src/main/resources/application.yaml)：高关注度的 Java 内容项目为资源配置长期缓存。本项目的图片会随商品下架、评价隐藏而立即失去公开可见性，因此没有照搬长期 `max-age`；资源读取仍参考成熟框架的流式方式。

## 改动与边界

`MediaService` 返回文件资源而非字节数组；文件仍保留大小、路径、符号链接与访问权限检查。公开响应提供 ETag 和 `no-cache`；已授权上传者的响应保持 `no-store`。相同 ETag 的请求只有通过当前可见性检查后才收到 304。公开请求仍执行数据库可见性查询，以保证下架和隐藏立即生效；本次优化的是图片内存分配与重复传输，并未消除该查询成本。

磁盘文件由当前应用写入后保持不变，上传生成的随机文件名充当版本标识；若以后允许覆盖同一文件名，需要改用文件内容的版本值。图片上传、异步处理和孤儿文件清理分别留给 M02、M03。
