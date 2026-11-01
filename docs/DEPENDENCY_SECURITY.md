# 依赖安全告警核对（2026-11-01）

IntelliJ/Mend 在 `pom.xml` 上显示 15 条告警，其中 Jackson 和 OkHttp 分别重复显示。以 Maven 依赖树及 `target/shiwushop-1.0-SNAPSHOT.jar` 的实际内容为准，不能把告警条数视为 15 个独立的生产漏洞。

| 组件 | 引入路径 | 当前处理 |
| --- | --- | --- |
| Tomcat Embed 11.0.26 | Spring Boot WebMVC | 升到 11.0.27；官方列出的 11.0.26 受影响问题已在 11.0.27 修复。 |
| Jackson Databind 3.1.7 | Flyway Core / Spring Boot 管理版本 | 升到 3.1.8，覆盖 CVE-2026-110879 的修复版本。 |
| RabbitMQ Java Client 5.30.0 | Spring AMQP | 升到 5.33.0，覆盖 CVE-2026-63337 的修复版本。 |
| OkHttp 3.12.13、Okio 1.15.0、dom4j 1.6.1、Bouncy Castle `bcprov-jdk15on` 1.62 | 支付宝 SDK | 仍进入运行包；须先验证 SDK 升级及支付兼容性，再处理旧传递依赖。 |
| ini4j 0.5.4、JAXB Core 4.0.9、dom4j 2.0.3、Bouncy Castle `bcpkix-jdk15on` 1.70 | 阿里云短信 SDK | 仍进入运行包；须核对新版 SDK 的依赖与短信调用兼容性。 |
| json-smart 2.6.0、ASM 9.7.1、android-json | Spring Boot 测试依赖 | 仅 `test` scope，未打入运行包；单独升级测试依赖时处理。 |

验证：`mvn dependency:tree` 确认三个修复版本被解析；`mvn verify` 运行 302 项测试，0 失败、0 错误、6 项按现有配置跳过。完整记录为 `logs/security-dependency-verify.log`。该测试未覆盖支付宝或阿里云真实渠道，其他运行时告警尚未消除。

依据：[Tomcat 11 安全公告](https://tomcat.apache.org/security-11)、[Jackson 安全公告](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-m8xf-2cr7-77ph)、[RabbitMQ 客户端安全公告](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-6g32-pxv4-2wfj)。
