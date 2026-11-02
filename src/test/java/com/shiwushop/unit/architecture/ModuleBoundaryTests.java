package com.shiwushop.unit.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 把本次整理的约束留在构建中，新增代码跨表或绕过接口时直接报告文件。 */
class ModuleBoundaryTests {
    private static final Path ROOT = Path.of("src/main/java/com/shiwushop");
    private static final Set<String> MODULES = Set.of("user", "merchant", "product", "trade", "payment", "aftersale", "common");
    private static final Pattern IMPORT = Pattern.compile("import (?:static )?(com\\.shiwushop\\.[^;]+);");

    @Test
    void productionPackagesMatchTheirFilesAndOnlyContainImplementedModules() throws Exception {
        try (var files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                var declaration = Pattern.compile("package ([^;]+);").matcher(text);
                assertTrue(declaration.find(), file.toString());
                String packageName = declaration.group(1);
                Path expected = Path.of("src/main/java", packageName.replace('.', '/'), file.getFileName().toString());
                assertEquals(expected, file, file.toString());
                if (!packageName.equals("com.shiwushop"))
                    assertTrue(MODULES.contains(packageName.split("\\.")[2]), file.toString());
            }
        }
    }

    @Test
    void businessModulesCannotUseForeignMappersOrImplementations() throws Exception {
        try (var files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Path relative = ROOT.relativize(file);
                if (relative.getNameCount() == 1) continue;
                String module = relative.getName(0).toString();
                var imports = IMPORT.matcher(Files.readString(file));
                while (imports.find()) {
                    String target = imports.group(1);
                    String dependency = target.split("\\.")[2];
                    if (module.equals(dependency) || dependency.equals("common")) continue;
                    assertNotEquals("common", module, file + " common 反向引用业务模块: " + target);
                    assertFalse(target.contains(".mapper.") || target.contains(".service.impl."),
                            file + " 跨模块访问实现: " + target);
                    if (target.contains(".service."))
                        assertTrue(Class.forName(target).isInterface(), file + " 应依赖业务接口: " + target);
                }
            }
        }
    }

    @Test
    void businessDependencyGraphHasNoCycles() throws Exception {
        Map<String, Set<String>> edges = new HashMap<>();
        for (String module : MODULES) edges.put(module, new HashSet<>());
        try (var files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Path relative = ROOT.relativize(file);
                if (relative.getNameCount() == 1) continue;
                String module = relative.getName(0).toString();
                var imports = IMPORT.matcher(Files.readString(file));
                while (imports.find()) {
                    String dependency = imports.group(1).split("\\.")[2];
                    if (!module.equals(dependency)) edges.get(module).add(dependency);
                }
            }
        }
        for (String start : MODULES) {
            Set<String> visited = new HashSet<>();
            Deque<String> pending = new ArrayDeque<>(edges.get(start));
            while (!pending.isEmpty()) {
                String next = pending.remove();
                assertNotEquals(start, next, "模块循环依赖: " + start);
                if (visited.add(next)) pending.addAll(edges.get(next));
            }
        }
    }

    @Test
    void everyMapperXmlReferencesExistingMovedClasses() throws Exception {
        Pattern types = Pattern.compile("(?:namespace|resultType|parameterType|type)=\"(com\\.shiwushop\\.[^\"]+)\"");
        try (var files = Files.walk(Path.of("src/main/resources/mapper"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                var matcher = types.matcher(Files.readString(file));
                while (matcher.find()) assertNotNull(Class.forName(matcher.group(1)), file.toString());
            }
        }
    }
}
