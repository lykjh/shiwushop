package com.shiwushop.unit.architecture;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** 跨四个 Maven 工程检查原有六个业务域，迁移不放宽原来的业务约束。 */
class ModuleBoundaryTests {
    static final Path PROJECT = Path.of(System.getProperty("shiwushop.project-root",
            Files.isDirectory(Path.of("shiwushop-core")) ? "." : "..")).toAbsolutePath().normalize();
    static final List<String> MAVEN_MODULES = List.of("shiwushop-common", "shiwushop-core", "shiwushop-search", "shiwushop-app");
    private static final Set<String> MODULES = Set.of("user", "merchant", "product", "trade", "payment", "aftersale", "common");
    private static final Pattern IMPORT = Pattern.compile("import (?:static )?(com\\.shiwushop\\.[^;]+);");

    /** 文件清单供各项检查复用，工程缺失时直接失败，避免空扫描误报通过。 */
    static List<Path> productionFiles() throws IOException {
        List<Path> result = new ArrayList<>();
        for (String module : MAVEN_MODULES) {
            try (var files = Files.walk(PROJECT.resolve(module).resolve("src/main/java"))) {
                result.addAll(files.filter(p -> p.toString().endsWith(".java")).toList());
            }
        }
        assertFalse(result.isEmpty(), "应扫描到四个工程的生产代码");
        return result;
    }

    @Test void productionPackagesMatchTheirFilesAndOnlyContainImplementedModules() throws Exception {
        for (Path file : productionFiles()) {
            var declaration = Pattern.compile("package ([^;]+);").matcher(Files.readString(file));
            assertTrue(declaration.find(), file.toString());
            String name = declaration.group(1);
            String mavenModule = PROJECT.relativize(file).getName(0).toString();
            Path expected = PROJECT.resolve(mavenModule).resolve("src/main/java")
                    .resolve(name.replace('.', '/')).resolve(file.getFileName());
            assertEquals(expected, file, file.toString());
            if (!name.equals("com.shiwushop")) assertTrue(MODULES.contains(name.split("\\.")[2]), file.toString());
        }
    }

    @Test void businessModulesCannotUseForeignMappersOrImplementations() throws Exception {
        for (Path file : productionFiles()) {
            var declaration = Pattern.compile("package ([^;]+);").matcher(Files.readString(file));
            assertTrue(declaration.find());
            if (declaration.group(1).equals("com.shiwushop")) continue;
            String owner = declaration.group(1).split("\\.")[2];
            var imports = IMPORT.matcher(Files.readString(file));
            while (imports.find()) {
                String target = imports.group(1), dependency = target.split("\\.")[2];
                if (owner.equals(dependency) || dependency.equals("common")) continue;
                assertNotEquals("common", owner, file + " common 反向引用业务模块: " + target);
                assertFalse(target.contains(".mapper.") || target.contains(".service.impl."), file + " 跨业务域访问实现: " + target);
                if (target.contains(".service.")) assertTrue(Class.forName(target).isInterface(), file + " 应依赖业务接口: " + target);
            }
        }
    }

    @Test void businessDependencyGraphHasNoCycles() throws Exception {
        Map<String, Set<String>> edges = new HashMap<>();
        for (String module : MODULES) edges.put(module, new HashSet<>());
        for (Path file : productionFiles()) {
            var declaration = Pattern.compile("package ([^;]+);").matcher(Files.readString(file));
            assertTrue(declaration.find());
            if (declaration.group(1).equals("com.shiwushop")) continue;
            String owner = declaration.group(1).split("\\.")[2];
            var imports = IMPORT.matcher(Files.readString(file));
            while (imports.find()) {
                String dependency = imports.group(1).split("\\.")[2];
                if (!owner.equals(dependency)) edges.get(owner).add(dependency);
            }
        }
        for (String start : MODULES) {
            Set<String> visited = new HashSet<>();
            Deque<String> pending = new ArrayDeque<>(edges.get(start));
            while (!pending.isEmpty()) {
                String next = pending.remove();
                assertNotEquals(start, next, "业务域循环依赖: " + start);
                if (visited.add(next)) pending.addAll(edges.get(next));
            }
        }
    }

    @Test void everyMapperXmlReferencesExistingMovedClasses() throws Exception {
        Pattern types = Pattern.compile("(?:namespace|resultType|parameterType|type)=\"(com\\.shiwushop\\.[^\"]+)\"");
        try (var files = Files.walk(PROJECT.resolve("shiwushop-core/src/main/resources/mapper"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                var matcher = types.matcher(Files.readString(file));
                while (matcher.find()) assertNotNull(Class.forName(matcher.group(1)), file.toString());
            }
        }
    }
}
