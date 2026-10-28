# M02 图片上传内存修复

## 旧方法与问题

`MediaServiceImpl.upload` 先调用 `MultipartFile.getBytes()`，使整份原图成为一个额外的堆内存数组，然后再解码成 `BufferedImage`。10 MiB 上传和多个同时请求会叠加这份原图内存。接口原本还在请求线程同步编码，最多同时处理 2 张图片；本次只处理额外原图数组这一处问题。

## 参考与选择

- [mall 的 MinIO 上传实现](https://github.com/macrozheng/mall/blob/master/mall-admin/src/main/java/com/macro/mall/controller/MinioController.java)使用 `MultipartFile.getInputStream()` 把上传流交给存储层。参考其流式入口，但本项目仍须先校验图片真实格式、像素并重新编码，因此没有直接保存原文件。
- [Spring MVC multipart 文档](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/multipart.html)说明上传文件由 Servlet multipart 解析提供，文件大小和落盘阈值由容器配置。本项目已有 10 MB 请求限制，服务层继续按实际读取字节数检查上限。

本次将原图以 8 KiB 缓冲流入临时文件，同时计算 SHA-256；ImageIO 从临时文件读取。完整解码后的像素仍在内存中，编码仍同步执行。与异步任务方案相比，这次无需更改响应协议、数据库迁移或图片引用流程；代价是处理耗时仍占用请求线程，超过 2 个并发处理的请求仍会收到 429。若以后要消除这些限制，需要单独设计任务状态、容量与重启恢复，不在这次小修复中混入。

## 验证

`MediaUploadUnitTests` 验证不调用 `getBytes()`、记录原图真实大小与哈希、清理临时文件，并拒绝实际内容超过 10 MiB 的上传。现有 `MediaSecurityTests` 验证真实图片、伪装格式、超大图片、像素上限和存储失败的 HTTP 行为。原始 Maven 输出保存在本地 `logs/M02-small-*.log`。
