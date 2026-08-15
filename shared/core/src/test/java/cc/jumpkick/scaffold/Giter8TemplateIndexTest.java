// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Giter8TemplateIndexTest {

    @Test
    void catalog_only_has_layout_and_languages() {
        var entries = Giter8TemplateIndex.catalogOnly();
        assertThat(entries).isNotEmpty();
        assertThat(Giter8ShortNames.find("ktor-3")).get().satisfies(e -> {
            assertThat(e.layout()).isEqualTo(Giter8ShortNames.LAYOUT_SIMPLE);
            assertThat(e.supports("kotlin")).isTrue();
        });
        assertThat(Giter8ShortNames.find("spring-boot-webmvc")).get().satisfies(e -> {
            assertThat(e.layout()).isEqualTo(Giter8ShortNames.LAYOUT_TRADITIONAL);
            assertThat(e.supports("java")).isTrue();
        });
        assertThat(Giter8ShortNames.find("grails-8")).get().satisfies(e -> {
            assertThat(e.layout()).isEqualTo(Giter8ShortNames.LAYOUT_CUSTOM);
            assertThat(e.supports("groovy")).isTrue();
        });
    }

    @Test
    void disk_props_overlay_catalog(@TempDir Path temp) throws Exception {
        Path g8 = temp.resolve("ktor-3.g8");
        Files.createDirectories(g8);
        Files.writeString(g8.resolve("default.properties"), """
                name=my-ktor
                jk_languages=kotlin
                jk_layout=simple
                """);
        var list = Giter8TemplateIndex.build(List.of(temp));
        var ktor =
                list.stream().filter(e -> e.id().equals("ktor-3")).findFirst().orElseThrow();
        assertThat(ktor.languages()).containsExactly("kotlin");
        assertThat(ktor.layout()).isEqualTo(Giter8ShortNames.LAYOUT_SIMPLE);
    }

    @Test
    void unknown_short_name_from_disk_is_appended(@TempDir Path temp) throws Exception {
        Path g8 = temp.resolve("acme-lib.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), """
                name=Acme Lib
                jk_languages=java
                jk_layout=traditional
                """);
        var list = Giter8TemplateIndex.build(List.of(temp));
        assertThat(list.stream().map(Giter8ShortNames.Entry::id)).contains("acme-lib");
        var e = list.stream().filter(x -> x.id().equals("acme-lib")).findFirst().orElseThrow();
        assertThat(e.layout()).isEqualTo(Giter8ShortNames.LAYOUT_TRADITIONAL);
        assertThat(e.supports("java")).isTrue();
    }

    @Test
    void infer_layout_from_tree(@TempDir Path temp) throws Exception {
        Path simple = temp.resolve("simple.g8");
        Files.createDirectories(simple.resolve("src/main/g8/src"));
        Files.writeString(simple.resolve("default.properties"), "name=s\n");
        assertThat(Giter8TemplateIndex.inferLayout(simple)).isEqualTo(Giter8ShortNames.LAYOUT_SIMPLE);

        Path trad = temp.resolve("trad.g8");
        Files.createDirectories(trad.resolve("src/main/g8/src/main/java"));
        Files.writeString(trad.resolve("default.properties"), "name=t\n");
        assertThat(Giter8TemplateIndex.inferLayout(trad)).isEqualTo(Giter8ShortNames.LAYOUT_TRADITIONAL);

        Path grails = temp.resolve("g.g8");
        Files.createDirectories(grails.resolve("src/main/g8/grails-app"));
        Files.writeString(grails.resolve("default.properties"), "name=g\n");
        assertThat(Giter8TemplateIndex.inferLayout(grails)).isEqualTo(Giter8ShortNames.LAYOUT_CUSTOM);
    }

    @Test
    void layout_from_properties() {
        assertThat(Giter8ShortNames.layoutFromProperties(Map.of("jk_layout", "traditional")))
                .contains(Giter8ShortNames.LAYOUT_TRADITIONAL);
        assertThat(Giter8ShortNames.layoutFromProperties(Map.of("layout", "simple")))
                .contains(Giter8ShortNames.LAYOUT_SIMPLE);
        assertThat(Giter8ShortNames.layoutFromProperties(Map.of())).isEmpty();
    }

    @Test
    void pass_two_probe_skips_ids_already_overlaid_in_pass_one(@TempDir Path temp) throws Exception {
        // The pass-2 deep DFS must only run for ids pass 1 did not overlay.
        Path g8 = temp.resolve("ktor-3.g8");
        Files.createDirectories(g8);
        Files.writeString(g8.resolve("default.properties"), "jk_languages=kotlin\njk_layout=simple\n");
        var byId = new LinkedHashMap<String, Giter8ShortNames.Entry>();
        for (var e : Giter8ShortNames.entries()) byId.put(e.id(), e);
        var overlaid = new HashSet<String>();
        Giter8TemplateIndex.scanRoot(temp, byId, overlaid);
        assertThat(overlaid).containsExactly("ktor-3");
        var probe = Giter8TemplateIndex.idsNeedingProbe(byId.values(), overlaid);
        assertThat(probe).doesNotContain("ktor-3").contains("java-cli", "quarkus");
    }

    @Test
    void resolve_short_name_finds_dogfood_templates_dir(@TempDir Path temp) throws Exception {
        // Monorepo shape: <root>/templates/<name>.g8 and parent dir under <root>/…
        Path g8 = temp.resolve("templates").resolve("spring-boot-webmvc.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), "name=demo\njk_languages=java\njk_layout=traditional\n");
        Path parent = temp.resolve("apps");
        Files.createDirectories(parent);
        assertThat(Giter8TemplateIndex.resolveShortName("spring-boot-webmvc", parent))
                .isPresent()
                .get()
                .satisfies(p -> assertThat(p.getFileName().toString()).isEqualTo("spring-boot-webmvc.g8"));
    }
}
