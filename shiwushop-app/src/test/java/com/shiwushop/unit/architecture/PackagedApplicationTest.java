package com.shiwushop.unit.architecture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** package 后显式执行，防止源码自测与交付 JAR 使用不同的业务类、SQL 或配置。 */
@EnabledIfSystemProperty(named = "app.artifact.path", matches = ".+")
class PackagedApplicationTest {
    @Test
    void packagedModulesMatchTheTestedClassesAndResources() throws Exception {
        Path root = Path.of(System.getProperty("shiwushop.project-root"));
        Path artifact = Path.of(System.getProperty("app.artifact.path"));
        try (var jar = new ZipFile(artifact.toFile())) {
            for (String module : List.of("app", "core", "common", "search")) {
                Map<String, byte[]> packaged = new HashMap<>();
                if (module.equals("app")) {
                    for (var entry : jar.stream()
                            .filter(e -> !e.isDirectory() && e.getName().startsWith("BOOT-INF/classes/")).toList()) {
                        try (var input = jar.getInputStream(entry)) {
                            packaged.put(entry.getName().substring("BOOT-INF/classes/".length()), input.readAllBytes());
                        }
                    }
                } else {
                    var entry = jar.getEntry("BOOT-INF/lib/shiwushop-" + module + "-1.0-SNAPSHOT.jar");
                    assertNotNull(entry, module + " 模块未打包");
                    try (var input = new ZipInputStream(jar.getInputStream(entry))) {
                        for (var nested = input.getNextEntry(); nested != null; nested = input.getNextEntry()) {
                            if (!nested.isDirectory())
                                packaged.put(nested.getName(), input.readAllBytes());
                        }
                    }
                }
                Path classes = root.resolve("shiwushop-" + module + "/target/classes");
                try (var files = Files.walk(classes)) {
                    var entries = files.filter(Files::isRegularFile).toList();
                    assertFalse(entries.isEmpty(), module + " 编译产物缺失");
                    for (var file : entries) {
                        String name = classes.relativize(file).toString().replace('\\', '/');
                        byte[] content = packaged.get(name);
                        // Boot 重打包把应用 META-INF 放在外层，依赖模块仍保留在嵌套 JAR。
                        if (module.equals("app") && name.startsWith("META-INF/")) {
                            var entry = jar.getEntry(name);
                            assertNotNull(entry, name);
                            try (var input = jar.getInputStream(entry)) {
                                content = input.readAllBytes();
                            }
                        }
                        assertArrayEquals(Files.readAllBytes(file), content, module + "/" + name);
                    }
                }
                if (module.equals("app")) {
                    assertTrue(packaged.containsKey("com/shiwushop/marketing/MonthlyCouponScheduler.class"));
                    assertTrue(
                            packaged.containsKey("com/shiwushop/marketing/controller/MonthlyCouponController.class"));
                }
                if (module.equals("core")) {
                    assertTrue(packaged.containsKey(
                            "com/shiwushop/marketing/service/impl/MarketingOrderSettlementImpl.class"));
                    assertTrue(packaged.containsKey("db/migration/V31__check_normal_settlement_amounts.sql"));
                }
            }
        }
    }
}
