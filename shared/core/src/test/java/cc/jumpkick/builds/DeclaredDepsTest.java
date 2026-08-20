// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclaredDepsTest {

    @Test
    void collect_standalone_skips_path_git_workspace(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "demo"
                version = "0.1.0"

                [dependencies]
                jspecify = "1.0.0"
                guava = "33.0.0"
                local = { path = "../other" }
                """);

        Set<String> deps = DeclaredDeps.collect(root);
        assertThat(deps).contains("jspecify", "guava");
        assertThat(deps).noneMatch(d -> d.startsWith("path:") || d.startsWith("workspace:"));
    }

    @Test
    void collect_workspace_unions_module_deps(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["api", "app"]

                [dependencies]
                jspecify = "1.0.0"
                """);
        Path api = root.resolve("api");
        Path app = root.resolve("app");
        Files.createDirectories(api);
        Files.createDirectories(app);
        Files.writeString(api.resolve("jk.toml"), """
                group = "com.example"
                name = "api"
                version = "1.0.0"

                [dependencies]
                guava = "33.0.0"
                """);
        Files.writeString(app.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                api = { workspace = true }
                lombok = "1.18.34"
                """);

        Set<String> deps = DeclaredDeps.collect(root);
        assertThat(deps).contains("jspecify", "guava", "lombok");
        assertThat(deps).doesNotContain("api");
    }

    @Test
    void normalize_prefers_catalog_short_name() {
        LibraryCatalog catalog = LibraryCatalog.of(Map.of(
                "jspecify", new LibraryCatalog.Module("org.jspecify", "jspecify"),
                "guava", new LibraryCatalog.Module("com.google.guava", "guava")));

        Dependency shortName = Dependency.of("jspecify", "org.jspecify:jspecify", VersionSelector.parse("1.0.0"));
        assertThat(DeclaredDeps.normalize(shortName, catalog)).isEqualTo("jspecify");

        Dependency byModule = Dependency.of("guava-weird", "com.google.guava:guava", VersionSelector.parse("33.0.0"));
        assertThat(DeclaredDeps.normalize(byModule, catalog)).isEqualTo("guava");

        Dependency unknown = Dependency.of("widget", "Com.Example:Widget", VersionSelector.parse("1.0.0"));
        assertThat(DeclaredDeps.normalize(unknown, catalog)).isEqualTo("com.example:widget");
    }

    @Test
    void collect_missing_jk_toml_is_empty(@TempDir Path root) {
        assertThat(DeclaredDeps.collect(root)).isEmpty();
    }
}
