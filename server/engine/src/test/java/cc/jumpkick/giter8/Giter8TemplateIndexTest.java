// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Giter8TemplateIndexTest {

    @Test
    void scans_lang_framework_name_and_resolves(@TempDir Path temp) throws Exception {
        writeTemplate(temp, "java", "none", "hello", "Hello app", "\"simple\"");
        writeTemplate(temp, "kotlin", "none", "hello", "Hello app", "\"simple\"");
        writeTemplate(temp, "java", "spring-boot", "hello", "Boot hello", "\"traditional\"");
        writeTemplate(temp, "groovy", "grails", "hello", "Grails hello", "\"custom\"");

        var list = Giter8TemplateIndex.picker(List.of(temp));
        assertThat(list.stream().map(TemplateSpec::id))
                .contains("java/none/hello", "kotlin/none/hello", "java/spring-boot/hello", "groovy/grails/hello");

        assertThat(Giter8TemplateIndex.resolve("hello", "java", List.of(temp)))
                .get()
                .extracting(TemplateSpec::id)
                .isEqualTo("java/none/hello");
        assertThat(Giter8TemplateIndex.resolve("hello", "kotlin", List.of(temp)))
                .get()
                .extracting(TemplateSpec::id)
                .isEqualTo("kotlin/none/hello");
        assertThat(Giter8TemplateIndex.resolve("spring-boot/hello", "java", List.of(temp)))
                .get()
                .extracting(TemplateSpec::id)
                .isEqualTo("java/spring-boot/hello");
        assertThat(Giter8TemplateIndex.resolve("grails/hello", "java", List.of(temp)))
                .get()
                .extracting(TemplateSpec::id)
                .isEqualTo("groovy/grails/hello");
        assertThat(Giter8TemplateIndex.resolve("java/spring-boot/hello", null, List.of(temp)))
                .get()
                .extracting(TemplateSpec::id)
                .isEqualTo("java/spring-boot/hello");
    }

    @Test
    void source_clones_with_arbitrary_cache_keys_are_scanned(@TempDir Path temp) throws Exception {
        // [templates.sources] clones land under the cache root named by their cache key; a
        // non-GitHub host produces keys like this, which a name allowlist would have skipped.
        Path clone = temp.resolve("git.example_corp_starters_main");
        writeTemplate(clone, "java", "none", "corp-starter", "Corp starter", "\"traditional\"");

        assertThat(Giter8TemplateIndex.resolve("corp-starter", "java", List.of(temp)))
                .get()
                .extracting(TemplateSpec::id)
                .isEqualTo("java/none/corp-starter");
    }

    @Test
    void bare_framework_name_lists_templates(@TempDir Path temp) throws Exception {
        writeTemplate(temp, "java", "spring-boot", "hello", "h", "\"traditional\"");
        writeTemplate(temp, "java", "spring-boot", "webmvc", "w", "\"traditional\"");
        assertThatThrownBy(() -> Giter8TemplateIndex.resolve("spring-boot", "java", List.of(temp)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is a framework")
                .hasMessageContaining("hello")
                .hasMessageContaining("webmvc");
    }

    @Test
    void metadata_mismatch_is_skipped(@TempDir Path temp) throws Exception {
        Path g8 = temp.resolve("java/none/hello.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve(".jk-template.toml"), """
                language = "kotlin"
                framework = "none"
                name = "hello"
                description = "wrong lang"
                """);
        Files.writeString(g8.resolve("default.properties"), "name=x\n");
        var list = Giter8TemplateIndex.picker(List.of(temp));
        assertThat(list.stream().map(TemplateSpec::id)).doesNotContain("java/none/hello");
    }

    @Test
    void ignores_legacy_lang_name_g8(@TempDir Path temp) throws Exception {
        Path g8 = temp.resolve("java/cli.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), "name=cli\n");
        Files.writeString(g8.resolve(".jk-template.toml"), """
                language = "java"
                framework = "none"
                name = "cli"
                description = "legacy path"
                """);
        assertThat(Giter8TemplateIndex.picker(List.of(temp)))
                .noneMatch(s -> TemplateSpec.SOURCE_CATALOG.equals(s.source()));
    }

    @Test
    void default_lang_prefers_java_then_kotlin_then_groovy() {
        assertThat(Giter8ShortNames.defaultLang(List.of("groovy", "kotlin", "java")))
                .contains("java");
        assertThat(Giter8ShortNames.defaultLang(List.of("groovy", "kotlin"))).contains("kotlin");
        assertThat(Giter8ShortNames.defaultLang(List.of("groovy"))).contains("groovy");
        assertThat(Giter8ShortNames.defaultLang(List.of())).isEmpty();
    }

    private static void writeTemplate(Path root, String lang, String fw, String name, String desc, String layouts)
            throws Exception {
        Path g8 = root.resolve(lang).resolve(fw).resolve(name + ".g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve(".jk-template.toml"), """
                language = "%s"
                framework = "%s"
                name = "%s"
                description = "%s"
                layouts = [%s]
                """.formatted(lang, fw, name, desc, layouts));
        Files.writeString(g8.resolve("default.properties"), "name=" + name + "\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name = \"$name$\"\n");
    }
}
