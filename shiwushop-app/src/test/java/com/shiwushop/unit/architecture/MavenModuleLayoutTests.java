package com.shiwushop.unit.architecture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** 验证工程依赖及 Web/业务隔离；业务域层面继续使用原有结构测试。 */
class MavenModuleLayoutTests {
    private static final Path PROJECT = ModuleBoundaryTests.PROJECT;
    private static final Map<String, Set<String>> DEPENDENCIES = Map.of(
            "shiwushop-common", Set.of(),
            "shiwushop-core", Set.of("shiwushop-common"),
            "shiwushop-search", Set.of("shiwushop-common", "shiwushop-core"),
            "shiwushop-app", Set.of("shiwushop-common", "shiwushop-core", "shiwushop-search"));

    @Test void parentAggregatesExactlyFourExistingModules() throws Exception {
        var parent = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(PROJECT.resolve("pom.xml").toFile());
        assertEquals("pom", parent.getElementsByTagName("packaging").item(0).getTextContent());
        Set<String> modules = new HashSet<>(); var nodes = parent.getElementsByTagName("module");
        for (int i = 0; i < nodes.getLength(); i++) modules.add(nodes.item(i).getTextContent());
        assertEquals(DEPENDENCIES.keySet(), modules);
    }

    @Test void declaredDependenciesOnlyPointTowardLowerLayers() throws Exception {
        for (var entry : DEPENDENCIES.entrySet()) {
            var pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(PROJECT.resolve(entry.getKey()+"/pom.xml").toFile());
            Set<String> found = new HashSet<>(); var nodes = pom.getElementsByTagName("dependency");
            for (int i = 0; i < nodes.getLength(); i++) {
                var dependency = (Element) nodes.item(i);
                String group = dependency.getElementsByTagName("groupId").item(0).getTextContent();
                if (group.equals("com")) found.add(dependency.getElementsByTagName("artifactId").item(0).getTextContent());
            }
            assertEquals(entry.getValue(), found, entry.getKey());
        }
    }

    @Test void librariesContainNoServletRequestsOrWebEntrypoints() throws Exception {
        for (Path file : ModuleBoundaryTests.productionFiles()) {
            if (PROJECT.relativize(file).getName(0).toString().equals("shiwushop-app")) continue;
            String text = Files.readString(file);
            assertFalse(Pattern.compile("import (jakarta\\.servlet|org\\.springframework\\.web)\\.").matcher(text).find(), file.toString());
            assertFalse(Pattern.compile("(?m)^@(RestController|Controller|RestControllerAdvice)\\b").matcher(text).find(), file.toString());
        }
    }

    @Test void sourceDependenciesFollowThePhysicalModuleOwnership() throws Exception {
        Map<String, String> owners = new HashMap<>();
        for (Path file : ModuleBoundaryTests.productionFiles()) {
            var declaration = Pattern.compile("package ([^;]+);").matcher(Files.readString(file)); assertTrue(declaration.find());
            String name = declaration.group(1)+"."+file.getFileName().toString().replace(".java", "");
            assertNull(owners.put(name, PROJECT.relativize(file).getName(0).toString()), "重复类型: "+name);
        }
        for (Path file : ModuleBoundaryTests.productionFiles()) {
            String owner = PROJECT.relativize(file).getName(0).toString();
            var references = Pattern.compile("com\\.shiwushop\\.[A-Za-z0-9_.$]+").matcher(Files.readString(file));
            while (references.find()) {
                String type = references.group();
                while (!owners.containsKey(type) && type.contains(".")) type = type.substring(0,type.lastIndexOf('.'));
                if (!owners.containsKey(type) || owners.get(type).equals(owner)) continue;
                assertTrue(DEPENDENCIES.get(owner).contains(owners.get(type)), file+" 反向依赖 "+type);
            }
        }
    }

    @Test void onlyAppDeclaresExecutableBootPackaging() throws Exception {
        for (String module : DEPENDENCIES.keySet()) {
            String text = Files.readString(PROJECT.resolve(module+"/pom.xml"));
            assertEquals(module.equals("shiwushop-app"), text.contains("<artifactId>spring-boot-maven-plugin</artifactId>"), module);
        }
    }

    @Test void businessSqlAndWebConfigurationHaveSingleOwners() {
        assertTrue(Files.isDirectory(PROJECT.resolve("shiwushop-core/src/main/resources/mapper")));
        assertTrue(Files.isDirectory(PROJECT.resolve("shiwushop-core/src/main/resources/db/migration")));
        assertTrue(Files.isRegularFile(PROJECT.resolve("shiwushop-app/src/main/resources/application.yml")));
        for (String module : List.of("shiwushop-common", "shiwushop-search"))
            assertFalse(Files.exists(PROJECT.resolve(module+"/src/main/resources/db/migration")), module);
    }
}
