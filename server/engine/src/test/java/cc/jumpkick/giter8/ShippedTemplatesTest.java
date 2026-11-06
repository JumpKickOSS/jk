// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShippedTemplatesTest {

    /** The templates jk ships: these names, framework none, in every language the checkout has them. */
    static final List<String> SHIPPED = List.of("app", "library");

    @AfterEach
    void dropMemo() {
        Giter8TemplateIndex.invalidate();
    }

    /**
     * The shipped copy is the checkout's {@code templates/} tree, byte for byte, and every {@code app}
     * and {@code library} there is shipped: an edit to one without the other fails here.
     */
    @Test
    void the_shipped_trees_are_the_checkouts_app_and_library_byte_for_byte() throws IOException {
        Path templates = RepoRoot.find(ShippedTemplatesTest.class).resolve("templates");
        Map<String, byte[]> expected = new TreeMap<>();
        try (Stream<Path> langs = Files.list(templates)) {
            for (Path lang : langs.filter(Files::isDirectory).toList()) {
                for (String name : SHIPPED) {
                    Path tree = lang.resolve(TemplateSpec.FRAMEWORK_NONE).resolve(name + ".g8");
                    if (Files.isDirectory(tree)) expected.putAll(files(templates, tree));
                }
            }
        }
        // Fake-green floor: java's two are always there.
        assertThat(expected.keySet())
                .anyMatch(k -> k.startsWith("java/none/app.g8/"))
                .anyMatch(k -> k.startsWith("java/none/library.g8/"));

        Path shipped = Objects.requireNonNull(ShippedTemplates.root(), "the shipped tree on the engine classpath");
        Map<String, byte[]> actual = files(shipped, shipped);

        assertThat(actual.keySet())
                .as(
                        "server/engine/src/main/resources/%s mirrors templates/*/none/{app,library}.g8 —"
                                + " copy the tree again",
                        ShippedTemplates.RESOURCE_ROOT)
                .containsExactlyElementsOf(expected.keySet());
        for (var e : expected.entrySet()) {
            assertThat(actual.get(e.getKey())).as("shipped %s", e.getKey()).isEqualTo(e.getValue());
        }
    }

    @Test
    void every_shipped_template_is_a_picker_row_jk_ships() {
        assertThat(ShippedTemplates.list())
                .extracting(TemplateSpec::id)
                .contains("java/none/app", "java/none/library", "kotlin/none/app", "groovy/none/app");
        assertThat(ShippedTemplates.list())
                .allSatisfy(s -> assertThat(s.source()).isEqualTo(TemplateSpec.SOURCE_SHIPPED));
    }

    @Test
    void with_no_catalogue_app_resolves_to_the_shipped_one(@TempDir Path empty) {
        var hit = Giter8TemplateIndex.resolve("app", null, List.of(empty));

        assertThat(hit).hasValueSatisfying(s -> {
            assertThat(s.id()).isEqualTo("java/none/app");
            assertThat(s.source()).isEqualTo(TemplateSpec.SOURCE_SHIPPED);
        });
        assertThat(Giter8TemplateIndex.resolve("library", "kotlin", List.of(empty)))
                .hasValueSatisfying(s -> assertThat(s.id()).isEqualTo("kotlin/none/library"));
    }

    @Test
    void a_catalogue_copy_wins_over_the_shipped_one(@TempDir Path catalogue) throws IOException {
        Path app = Files.createDirectories(catalogue.resolve("java/none/app.g8"));
        Files.writeString(app.resolve(".jk-template.toml"), """
                language = "java"
                framework = "none"
                name = "app"
                description = "The catalogue's app"
                """);
        Files.writeString(app.resolve("default.properties"), "name=app\n");

        var hit = Giter8TemplateIndex.resolve("app", null, List.of(catalogue));

        assertThat(hit).hasValueSatisfying(s -> {
            assertThat(s.description()).isEqualTo("The catalogue's app");
            assertThat(s.source()).isNotEqualTo(TemplateSpec.SOURCE_SHIPPED);
        });
        // A template the clone lacks (an older catalogue) is still served from jk's copy.
        assertThat(Giter8TemplateIndex.resolve("library", null, List.of(catalogue)))
                .hasValueSatisfying(s -> assertThat(s.source()).isEqualTo(TemplateSpec.SOURCE_SHIPPED));
    }

    @Test
    void materialize_extracts_the_whole_tree() throws IOException {
        TemplateSpec app = ShippedTemplates.list().stream()
                .filter(s -> s.id().equals("java/none/app"))
                .findFirst()
                .orElseThrow();
        Path dir = ShippedTemplates.materialize(app);
        try {
            Path src = Objects.requireNonNull(ShippedTemplates.root()).resolve("java/none/app.g8");
            assertThat(files(dir, dir).keySet())
                    .containsExactlyElementsOf(files(src, src).keySet());
            assertThat(dir.resolve(JkTemplateToml.FILE_NAME)).isRegularFile();
        } finally {
            PathUtil.deleteRecursivelyOrThrow(dir);
        }
    }

    /** Every regular file under {@code tree}, keyed by its {@code /}-separated path from {@code base}. */
    private static Map<String, byte[]> files(Path base, Path tree) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(tree)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                String rel = base.relativize(f).toString().replace('\\', '/');
                out.put(rel, Files.readAllBytes(f));
            }
        }
        return out;
    }
}
