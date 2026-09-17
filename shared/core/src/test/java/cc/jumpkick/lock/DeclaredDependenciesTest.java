// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The graph size of a first lock, read off the manifests: distinct across scopes and members. */
class DeclaredDependenciesTest {

    @Test
    void counts_distinct_modules_across_scopes_and_workspace_members(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.acme"
                name = "root"
                version = "0.1.0"

                [workspace]
                modules = ["a"]

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre" }

                [test-dependencies]
                assertj = { group = "org.assertj", name = "assertj-core", version = "3.26.0" }
                """);
        Files.createDirectories(tmp.resolve("a"));
        Files.writeString(tmp.resolve("a/jk.toml"), """
                name = "a"

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre" }
                jackson = { group = "com.fasterxml.jackson.core", name = "jackson-databind", version = "2.17.0" }
                """);

        assertThat(DeclaredDependencies.countDistinct(tmp)).isEqualTo(3);
        assertThat(DeclaredDependencies.countDistinct(tmp.resolve("nowhere"))).isZero();
    }
}
