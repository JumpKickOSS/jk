// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.PathSource;
import cc.jumpkick.model.VersionSelector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrossPackageFeaturesTest {

    @Test
    void enabling_feature_pulls_library_optional_dep(@TempDir Path dir) throws Exception {
        Path lib = dir.resolve("widget");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name = "widget"
                version = "0.1.0"

                [dependencies]
                core = { group = "com.example", name = "widget-core", version = "1.0.0" }
                mysql = { group = "com.mysql", name = "mysql-connector-j", version = "8.0.0", optional = true }

                [features]
                default = []
                mysql = { deps = ["mysql"] }
                """);

        Dependency consumer =
                Dependency.pathByName("widget", new PathSource("widget")).withFeatures(List.of("mysql"), false);

        CrossPackageFeatures.Result r = CrossPackageFeatures.expand(dir, List.of(consumer));
        assertThat(r.extraRoots()).containsKey("com.mysql:mysql-connector-j");
        assertThat(r.activatedFeaturesByModule().get("path:widget")).containsExactly("mysql");
    }

    @Test
    void default_features_false_withholds_defaults(@TempDir Path dir) throws Exception {
        Path lib = dir.resolve("widget");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name = "widget"
                version = "0.1.0"

                [dependencies]
                extra = { group = "com.example", name = "extra", version = "1.0.0", optional = true }

                [features]
                default = ["extra-feat"]
                extra-feat = { deps = ["extra"] }
                """);

        Dependency consumer =
                Dependency.pathByName("widget", new PathSource("widget")).withFeatures(List.of(), false);

        CrossPackageFeatures.Result r = CrossPackageFeatures.expand(dir, List.of(consumer));
        assertThat(r.extraRoots()).isEmpty();
        assertThat(r.activatedFeaturesByModule().get("path:widget")).isEmpty();
    }

    @Test
    void unknown_feature_errors(@TempDir Path dir) throws Exception {
        Path lib = dir.resolve("widget");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name = "widget"
                version = "0.1.0"

                [features]
                default = []
                """);

        Dependency consumer =
                Dependency.pathByName("widget", new PathSource("widget")).withFeatures(List.of("nope"), true);

        assertThatThrownBy(() -> CrossPackageFeatures.expand(dir, List.of(consumer)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown feature");
    }

    @Test
    void no_selection_is_noop() {
        Dependency plain = new Dependency("com.foo:bar", VersionSelector.parse("=1.0"));
        CrossPackageFeatures.Result r = CrossPackageFeatures.expand(Path.of("."), List.of(plain));
        assertThat(r.extraRoots()).isEmpty();
    }
}
