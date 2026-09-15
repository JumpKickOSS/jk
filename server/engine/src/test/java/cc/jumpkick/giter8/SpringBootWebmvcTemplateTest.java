// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.model.JkVersion;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringBootWebmvcTemplateTest {

    // The registry is process-wide. Installing a bare spring-boot descriptor over the real one
    // leaves every later test in this JVM without its contributions — the packager-dependency
    // suite saw none — so the real entry is put back after each test.
    private @Nullable PluginDescriptor previous;
    private @Nullable Path previousArchive;

    @BeforeEach
    void rememberSpringBoot() {
        previous = PluginTableRegistry.byTable("spring-boot").orElse(null);
        previousArchive = PluginTableRegistry.archive("spring-boot");
    }

    @AfterEach
    void restoreSpringBoot() {
        if (previous != null) PluginTableRegistry.restoreBuiltIn(previous, previousArchive);
    }

    @Test
    void java_webmvc_applies_workspace_tree(@TempDir Path dir) throws Exception {
        Path tmpl = repoTemplate("java");
        assumeTrue(tmpl != null && Files.isDirectory(tmpl), "spring-boot java/webmvc template on disk");
        Path dest = dir.resolve("notes");
        Giter8Apply.apply(
                tmpl,
                dest,
                Map.of("name", "notes", "organization", "com.acme", "package", "com.acme.notes", "java", "26"));
        assertThat(dest.resolve("jk.toml")).content().contains("modules = [\"data\", \"client\", \"server\"]");
        assertThat(dest.resolve("jk.toml")).content().contains("java = 26");
        assertThat(dest.resolve("data/src/main/java/com/acme/notes/data/note/Note.java"))
                .content()
                .contains("package com.acme.notes.data.note");
        assertThat(dest.resolve("data/src/main/resources/db/migration/V1__notes.sql"))
                .exists();
        assertThat(dest.resolve("server/src/main/java/com/acme/notes/server/Application.java"))
                .exists();
        assertThat(dest.resolve("server/src/test/java/com/acme/notes/server/NoteApiTest.java"))
                .content()
                .contains("Welcome to JumpKick");
        assertThat(dest.resolve("client/src/main/resources/static/index.html"))
                .content()
                .contains("unpkg.com/vue@3");
        assertThat(dest.resolve("server/src/main/resources/application.properties"))
                .content()
                .contains("spring.threads.virtual.enabled=true");
    }

    @Test
    void java_webmvc_simple_layout(@TempDir Path dir) throws Exception {
        Path tmpl = repoTemplate("java");
        assumeTrue(tmpl != null && Files.isDirectory(tmpl), "spring-boot java/webmvc template on disk");
        Path dest = dir.resolve("notes");
        Giter8Apply.apply(
                tmpl,
                dest,
                Map.of(
                        "name",
                        "notes",
                        "organization",
                        "com.acme",
                        "package",
                        "com.acme.notes",
                        "java",
                        "26",
                        "simple",
                        "yes"));
        assertThat(dest.resolve("data/src/com/acme/notes/data/note/Note.java")).exists();
        assertThat(dest.resolve("data/resources/db/migration/V1__notes.sql")).exists();
        assertThat(dest.resolve("server/src/com/acme/notes/server/Application.java"))
                .exists();
        assertThat(dest.resolve("server/test/src/com/acme/notes/server/NoteApiTest.java"))
                .exists();
        assertThat(dest.resolve("data/src/main/java")).doesNotExist();
    }

    @Test
    void kotlin_webmvc_applies_jooq_tree(@TempDir Path dir) throws Exception {
        Path tmpl = repoTemplate("kotlin");
        assumeTrue(tmpl != null && Files.isDirectory(tmpl), "spring-boot kotlin/webmvc template on disk");
        Path dest = dir.resolve("notes");
        Giter8Apply.apply(
                tmpl,
                dest,
                Map.of("name", "notes", "organization", "com.acme", "package", "com.acme.notes", "java", "26"));
        assertThat(dest.resolve("data/jk.toml")).content().contains("starter-jooq");
        assertThat(dest.resolve("data/src/main/kotlin/com/acme/notes/data/note/NoteRepository.kt"))
                .content()
                .contains("DSLContext");
        assertThat(dest.resolve("server/src/main/kotlin/com/acme/notes/server/Application.kt"))
                .exists();
        assertThat(dest.resolve("server/src/test/kotlin/com/acme/notes/server/NoteApiTest.kt"))
                .content()
                .contains("Welcome to JumpKick");
        assertThat(dest.resolve("jk.toml")).content().contains("kotlin = \"2.4.20\"");
    }

    @Test
    void materialize_webmvc_from_built_plugin_jar(@TempDir Path dir) throws Exception {
        Path jar = pluginJar();
        assumeTrue(jar != null, "jk-spring-boot jar on disk");
        var d = PluginDescriptors.parse("""
                [plugin]
                id = "spring-boot"
                table = "spring-boot"
                version = "1"
                """, "spring-boot.toml");
        PluginTableRegistry.putBuiltIn(d, jar);
        Path extracted = PluginTemplates.materialize("spring-boot", "java", "spring-boot", "webmvc");
        assertThat(extracted.resolve("default.properties")).exists();
        assertThat(extracted.resolve("src/main/g8/jk.toml")).exists();
        Path dest = dir.resolve("out");
        Giter8Apply.apply(
                extracted,
                dest,
                Map.of("name", "notes", "organization", "com.acme", "package", "com.acme.notes", "java", "26"));
        assertThat(dest.resolve("jk.toml")).exists();
        assertThat(dest.resolve("data/jk.toml")).exists();
    }

    private static @Nullable Path pluginJar() {
        // Version derived from the owner: a hand-typed version turned this into a silent
        // assume-skip on every version bump.
        String jarName = "jk-spring-boot-" + JkVersion.VERSION + ".jar";
        Path p = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 8 && p != null; i++) {
            Path jar = p.resolve("plugins/spring-boot/build/libs").resolve(jarName);
            if (Files.isRegularFile(jar)) return jar;
            p = p.getParent();
        }
        return null;
    }

    private static @Nullable Path repoTemplate(String lang) {
        Path p = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 8 && p != null; i++) {
            Path t = p.resolve("plugins/spring-boot/src/main/resources/templates")
                    .resolve(lang)
                    .resolve("spring-boot")
                    .resolve("webmvc.g8");
            if (Files.isDirectory(t.resolve("src/main/g8"))) return t;
            p = p.getParent();
        }
        return null;
    }
}
