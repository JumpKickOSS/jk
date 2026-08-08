// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Engine-hosted `jk new --<flag>` rendering from manifest [scaffold] data. */
class ScaffoldOpsTest {

    @Test
    void grails_scaffold_renders_grails_app_tree_and_toml_append(@TempDir Path tmp) {
        var files = ScaffoldOps.scaffold(
                tmp,
                Map.of(
                        "plugin", "grails",
                        "lang", "groovy",
                        "package", "com.example",
                        "simpleLayout", "true",
                        "sample", "true",
                        "baseToml", "[project]\nname = \"demo\"\n"));
        assertThat(files.error()).isNull();
        assertThat(files.paths())
                .anyMatch(p -> p.endsWith("jk.toml"))
                .anyMatch(p -> p.endsWith("grails-app/init/com/example/Application.groovy"))
                .anyMatch(p -> p.endsWith("grails-app/init/com/example/BootStrap.groovy"))
                .anyMatch(p -> p.endsWith("grails-app/domain/com/example/Note.groovy"))
                .anyMatch(p -> p.endsWith("grails-app/controllers/com/example/NoteController.groovy"))
                .anyMatch(p -> p.endsWith("grails-app/conf/application.yml"));
        String toml = files.contents()
                .get(files.paths().indexOf(tmp.resolve("jk.toml").toString()));
        assertThat(toml).contains("[grails]").contains("version = \"8.0.0-M4\"").contains("grails-web-boot");
        int note = -1;
        for (int i = 0; i < files.paths().size(); i++) {
            if (files.paths().get(i).endsWith("Note.groovy")) note = i;
        }
        assertThat(files.contents().get(note)).startsWith("package com.example");
    }

    @Test
    void spring_simple_layout_uses_mill_test_src(@TempDir Path tmp) {
        var files = ScaffoldOps.scaffold(
                tmp,
                Map.of(
                        "plugin", "spring",
                        "lang", "java",
                        "package", "com.example",
                        "simpleLayout", "true",
                        "sample", "true",
                        "baseToml", "[project]\nname = \"demo\"\n"));
        assertThat(files.error()).isNull();
        assertThat(files.paths())
                .anyMatch(p -> p.endsWith("src/com/example/Application.java"))
                .anyMatch(p -> p.endsWith("test/src/com/example/ApplicationTest.java"))
                .anyMatch(p -> p.endsWith("resources/application.properties"))
                .noneMatch(p -> p.contains("src/test/java") || p.contains("/test/com/"));
    }

    @Test
    void spring_traditional_layout_uses_maven_tree(@TempDir Path tmp) {
        var files = ScaffoldOps.scaffold(
                tmp,
                Map.of(
                        "plugin", "spring",
                        "lang", "java",
                        "package", "com.example",
                        "simpleLayout", "false",
                        "sample", "true",
                        "baseToml", "[project]\nname = \"demo\"\n"));
        assertThat(files.error()).isNull();
        assertThat(files.paths())
                .anyMatch(p -> p.endsWith("src/main/java/com/example/Application.java"))
                .anyMatch(p -> p.endsWith("src/test/java/com/example/ApplicationTest.java"));
    }

    @Test
    void unknown_flag_reports_an_error() {
        var files = ScaffoldOps.scaffold(Path.of("."), Map.of("plugin", "micronaut", "package", "x"));
        assertThat(files.error()).contains("micronaut");
    }

    @Test
    void quarkus_pom_interpolates_project_coords(@TempDir Path tmp) {
        var files = ScaffoldOps.scaffold(
                tmp,
                Map.of(
                        "plugin", "quarkus",
                        "lang", "java",
                        "package", "com.example",
                        "group", "com.example",
                        "name", "demo",
                        "version", "0.1.0",
                        "quarkus.version", "3",
                        "simpleLayout", "false",
                        "sample", "true",
                        "baseToml", "[project]\nname = \"demo\"\ngroup = \"com.example\"\n"));
        assertThat(files.error()).isNull();
        int pom = -1;
        for (int i = 0; i < files.paths().size(); i++) {
            if (files.paths().get(i).endsWith("pom.xml")) pom = i;
        }
        assertThat(pom).isGreaterThanOrEqualTo(0);
        String xml = files.contents().get(pom);
        assertThat(xml).contains("<groupId>com.example</groupId>");
        assertThat(xml).contains("<artifactId>demo</artifactId>");
        assertThat(xml).contains("<quarkus.platform.version>3</quarkus.platform.version>");
        assertThat(xml).doesNotContain("${group}").doesNotContain("${name}");
    }
}
