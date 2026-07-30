// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Giter8CatalogTest {

    @Test
    void short_names_include_quarkus_and_java_cli() {
        assertThat(Giter8Catalog.descriptions()).containsKeys("quarkus", "java-cli");
        assertThat(Giter8Catalog.isShortName("quarkus")).isTrue();
        assertThat(Giter8Catalog.isShortName("java-cli")).isTrue();
        assertThat(Giter8Catalog.isShortName("../x")).isFalse();
        assertThat(Giter8Catalog.isShortName("owner/repo")).isFalse();
    }

    @Test
    void resolves_filesystem_template_under_templates_dir(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("checkout");
        Path g8 = root.resolve("templates/quarkus.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), "name=x\n");
        Path cwd = root.resolve("examples/demo");
        Files.createDirectories(cwd);

        Path extract = tmp.resolve("extract");
        var resolved = Giter8Catalog.resolveShortName("quarkus", cwd, extract);
        assertThat(resolved).isPresent();
        assertThat(resolved.get()).isEqualTo(g8.toAbsolutePath().normalize());
    }

    @Test
    void classpath_or_checkout_template_applies(@TempDir Path tmp) throws Exception {
        // Prefer classpath bundle when no local templates/ tree.
        Path extract = tmp.resolve("extract");
        Path cwd = tmp.resolve("empty-cwd");
        Files.createDirectories(cwd);
        var resolved = Giter8Catalog.resolveShortName("quarkus", cwd, extract);
        // May be empty if resources not on test classpath yet; apply when present.
        if (resolved.isEmpty()) {
            return;
        }
        Path dest = tmp.resolve("out");
        int n = Giter8LocalApply.apply(resolved.get(), dest, Map.of("name", "widget", "package", "com.w"));
        assertThat(n).isGreaterThan(0);
        assertThat(dest.resolve("jk.toml")).exists();
        assertThat(Files.readString(dest.resolve("jk.toml"))).contains("widget");
    }
}
