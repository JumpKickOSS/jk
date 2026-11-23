// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Giter8 catalog's short names and where a short name resolves. Integration tier: resolving a
 * name no catalog holds runs {@code git} outside the test's temp roots, which keys the class by its
 * whole module, so in the fast tier any edit of the engine would run it again.
 */
@Tag("integration")
class Giter8CatalogTest {

    @Test
    void short_names_include_quarkus_java_and_kotlin_cli() {
        assertThat(Giter8Catalog.descriptions()).containsKeys("cli", "ktor-3");
        assertThat(Giter8Catalog.isShortName("quarkus")).isTrue();
        assertThat(Giter8Catalog.isShortName("cli")).isTrue();
        assertThat(Giter8Catalog.isShortName("ktor-3")).isTrue();
        assertThat(Giter8Catalog.isShortName("../x")).isFalse();
        assertThat(Giter8Catalog.isShortName("owner/repo")).isFalse();
    }

    @Test
    void resolves_filesystem_template_under_templates_dir(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("checkout");
        Path g8 = root.resolve("templates/java/cli.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), "name=x\n");
        Path cwd = root.resolve("examples/demo");
        Files.createDirectories(cwd);

        Path extract = tmp.resolve("extract");
        var resolved = Giter8Catalog.resolveShortName("cli", cwd, extract);
        assertThat(resolved).isPresent();
        assertThat(resolved.get()).isEqualTo(g8.toAbsolutePath().normalize());
    }

    @Test
    void classpath_bundle_removed_templates_via_cache_or_checkout(@TempDir Path tmp) throws Exception {
        // Classpath giter8 resources removed – resolution now requires a checkout walk-up
        // templates/ tree or a cached git clone. Use a unique name not in any catalog.
        Path extract = tmp.resolve("extract");
        Path cwd = tmp.resolve("empty-cwd");
        Files.createDirectories(cwd);
        var resolved = Giter8Catalog.resolveShortName("no-such-template-xyz", cwd, extract);
        assertThat(resolved).isEmpty();
    }
}
