// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryCatalogLayeringTest {

    private static final LibraryCatalog.Module BUNDLED_PICOCLI = new LibraryCatalog.Module("info.picocli", "picocli");

    private static final LibraryCatalog.Module FORK_PICOCLI = new LibraryCatalog.Module("io.fork", "picocli");

    @Test
    void project_overrides_shadow_bundled_entries() {
        LibraryCatalog bundled = LibraryCatalog.of(Map.of("picocli", BUNDLED_PICOCLI));
        LibraryCatalog effective = bundled.withProjectOverrides(Map.of("picocli", FORK_PICOCLI));

        assertThat(effective.lookup("picocli")).contains(FORK_PICOCLI);
        assertThat(effective.source("picocli"))
                .get()
                .extracting(LibraryCatalog.Source::layer)
                .isEqualTo("project");
    }

    @Test
    void project_layer_extends_bundled_without_collisions() {
        LibraryCatalog bundled = LibraryCatalog.of(Map.of("picocli", BUNDLED_PICOCLI));
        LibraryCatalog effective = bundled.withProjectOverrides(
                Map.of("internal-thing", new LibraryCatalog.Module("com.acme", "internal-thing")));

        assertThat(effective.lookup("picocli")).contains(BUNDLED_PICOCLI);
        assertThat(effective.lookup("internal-thing"))
                .get()
                .extracting(LibraryCatalog.Module::moduleKey)
                .isEqualTo("com.acme:internal-thing");
        assertThat(effective.source("picocli"))
                .get()
                .extracting(LibraryCatalog.Source::layer)
                .isEqualTo("test");
        assertThat(effective.source("internal-thing"))
                .get()
                .extracting(LibraryCatalog.Source::layer)
                .isEqualTo("project");
    }

    @Test
    void empty_project_overrides_return_the_same_catalog() {
        LibraryCatalog bundled = LibraryCatalog.of(Map.of("picocli", BUNDLED_PICOCLI));
        assertThat(bundled.withProjectOverrides(Map.of())).isSameAs(bundled);
        assertThat(bundled.withProjectOverrides(null)).isSameAs(bundled);
    }

    @Test
    void layer_names_walk_in_lookup_order() {
        LibraryCatalog bundled = LibraryCatalog.of(Map.of("a", BUNDLED_PICOCLI));
        LibraryCatalog withProject =
                bundled.withProjectOverrides(Map.of("a", new LibraryCatalog.Module("project", "a")));
        assertThat(withProject.layerNames()).containsExactly("project", "test");
    }

    @Test
    void system_layered_has_no_local_layer() {
        assertThat(LibraryCatalog.layered().layerNames()).doesNotContain("local");
    }

    @Test
    void for_project_loads_workspace_root_jk_libs(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("ws");
        Path mod = root.resolve("app");
        Files.createDirectories(mod);
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["app"]
                """);
        Files.writeString(mod.resolve("jk.toml"), """
                name = "app"
                """);
        Files.writeString(root.resolve("jk-libs.toml"), """
                [libraries]
                internal = "com.acme:internal"
                """);

        LibraryCatalog catalog = LibraryCatalog.forProject(mod);
        assertThat(catalog.lookup("internal"))
                .get()
                .extracting(LibraryCatalog.Module::moduleKey)
                .isEqualTo("com.acme:internal");
        assertThat(catalog.source("internal"))
                .get()
                .extracting(LibraryCatalog.Source::layer)
                .isEqualTo("project");
    }

    /**
     * Membership follows the one reading of {@code [workspace] modules}: a member selected by a glob
     * entry ({@code libs/*}) is as much a member as one listed literally, so its catalog root is the
     * workspace root and the root {@code jk-libs.toml} reaches it.
     */
    @Test
    void for_project_resolves_the_root_catalog_for_a_glob_listed_member(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("ws");
        Path mod = root.resolve("libs").resolve("foo");
        Files.createDirectories(mod);
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["libs/*"]
                """);
        Files.writeString(mod.resolve("jk.toml"), """
                name = "foo"
                """);
        Files.writeString(root.resolve("jk-libs.toml"), """
                [libraries]
                internal = "com.acme:internal"
                """);

        assertThat(LibraryCatalog.catalogRoot(mod))
                .isEqualTo(root.toAbsolutePath().normalize());
        assertThat(LibraryCatalog.forProject(mod).lookup("internal"))
                .get()
                .extracting(LibraryCatalog.Module::moduleKey)
                .isEqualTo("com.acme:internal");
    }

    /**
     * Catalog-root discovery reads {@code workspace.modules} through {@link
     * cc.jumpkick.config.TomlScan}, the owner of that scan, instead of a private line loop. The
     * private loop keyed on a {@code [workspace]} header and a line starting with {@code modules},
     * so the equivalent dotted spelling was invisible to it — a workspace root written that way
     * silently became "not a workspace", and its {@code jk-libs.toml} stopped reaching the module.
     */
    @Test
    void for_project_sees_a_workspace_declared_with_a_dotted_key(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("ws");
        Path mod = root.resolve("app");
        Files.createDirectories(mod);
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"
                workspace.modules = ["app"]
                """);
        Files.writeString(mod.resolve("jk.toml"), """
                name = "app"
                """);
        Files.writeString(root.resolve("jk-libs.toml"), """
                [libraries]
                internal = "com.acme:internal"
                """);

        assertThat(LibraryCatalog.forProject(mod).lookup("internal"))
                .get()
                .extracting(LibraryCatalog.Module::moduleKey)
                .isEqualTo("com.acme:internal");
    }

    @Test
    void for_project_rejects_module_local_jk_libs(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("ws");
        Path mod = root.resolve("app");
        Files.createDirectories(mod);
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["app"]
                """);
        Files.writeString(mod.resolve("jk.toml"), "name = \"app\"\n");
        Files.writeString(mod.resolve("jk-libs.toml"), """
                [libraries]
                x = "com.acme:x"
                """);

        assertThatThrownBy(() -> LibraryCatalog.forProject(mod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only allowed at the workspace root");
    }

    @Test
    void nested_standalone_project_is_not_the_ancestor_workspace_catalog(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("ws");
        Path nested = root.resolve("scratch").resolve("solo");
        Files.createDirectories(nested);
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["app"]
                """);
        Files.writeString(root.resolve("jk-libs.toml"), """
                [libraries]
                internal = "com.acme:from-workspace"
                """);
        Files.writeString(nested.resolve("jk.toml"), """
                group = "com.solo"
                name = "solo"
                version = "1.0.0"
                """);
        Files.writeString(nested.resolve("jk-libs.toml"), """
                [libraries]
                internal = "com.acme:from-nested"
                """);

        LibraryCatalog catalog = LibraryCatalog.forProject(nested);
        assertThat(catalog.lookup("internal"))
                .get()
                .extracting(LibraryCatalog.Module::moduleKey)
                .isEqualTo("com.acme:from-nested");
    }
}
