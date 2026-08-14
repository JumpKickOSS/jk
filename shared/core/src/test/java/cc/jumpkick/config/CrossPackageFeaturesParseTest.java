// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrossPackageFeaturesParseTest {

    @Test
    void parses_features_and_default_features_on_dep(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, """
                group = "com.acme"
                name = "app"
                version = "0.1.0"

                [dependencies]
                widget = { path = "../widget", features = ["mysql"], default-features = false }
                """);
        JkBuild build = JkBuildParser.parse(toml);
        Dependency d = build.dependencies().of(Scope.MAIN).getFirst();
        assertThat(d.library()).isEqualTo("widget");
        assertThat(d.isPath()).isTrue();
        assertThat(d.requestedFeatures()).containsExactly("mysql");
        assertThat(d.defaultFeatures()).isFalse();
        assertThat(d.hasFeatureSelection()).isTrue();
    }

    @Test
    void absent_feature_keys_mean_no_selection(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, """
                group = "com.acme"
                name = "app"
                version = "0.1.0"

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre" }
                """);
        Dependency d = JkBuildParser.parse(toml).dependencies().of(Scope.MAIN).getFirst();
        assertThat(d.hasFeatureSelection()).isFalse();
        assertThat(d.defaultFeatures()).isTrue();
        assertThat(d.requestedFeatures()).isEmpty();
    }
}
