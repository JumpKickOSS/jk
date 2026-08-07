// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkTemplatesConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
// Optional used for JkTemplatesConfig.Source rev
import org.junit.jupiter.api.io.TempDir;

class Giter8CatalogResolveTest {

    @Test
    void find_named_template_in_monorepo_layout(@TempDir Path tmp) throws Exception {
        Path mono = tmp.resolve("mono");
        Path g8 = mono.resolve("java-cli.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), "name=hello\n");
        assertThat(Giter8Git.findNamedTemplate(mono, "java-cli")).isPresent();
        assertThat(Giter8Git.findNamedTemplate(mono, "java-cli").get())
                .isEqualTo(g8.toAbsolutePath().normalize());
        assertThat(Giter8Git.findNamedTemplate(mono, "missing")).isEmpty();
    }

    @Test
    void find_named_under_templates_subdir(@TempDir Path tmp) throws Exception {
        Path mono = tmp.resolve("mono");
        Path g8 = mono.resolve("templates").resolve("kotlin-cli.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), "name=kt\n");
        assertThat(Giter8Git.findNamedTemplate(mono, "kotlin-cli"))
                .contains(g8.toAbsolutePath().normalize());
    }

    @Test
    void resolve_short_name_from_walk_up_before_git(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("checkout");
        Path g8 = root.resolve("templates/quarkus.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), "name=q\n");
        Path cwd = root.resolve("examples/demo");
        Files.createDirectories(cwd);
        Path extract = tmp.resolve("extract");
        // No network: offline config with bogus official URL
        var cfg = new JkTemplatesConfig("https://example.invalid/nope", List.of());
        Optional<Path> hit = Giter8Catalog.resolveShortName("quarkus", cwd, extract, cfg, List.of());
        assertThat(hit).isPresent();
        assertThat(hit.get()).isEqualTo(g8.toAbsolutePath().normalize());
    }

    @Test
    void single_template_matches_url_stem() {
        assertThat(Giter8Catalog.singleTemplateMatches(Path.of("."), "https://github.com/acme/cool-cli.g8", "cool-cli"))
                .isTrue();
        assertThat(Giter8Catalog.singleTemplateMatches(Path.of("."), "https://github.com/acme/other.git", "cool-cli"))
                .isFalse();
    }

    @Test
    void help_known_lists_sources() {
        var cfg = new JkTemplatesConfig(
                JkTemplatesConfig.DEFAULT_OFFICIAL,
                List.of(new JkTemplatesConfig.Source("acme", "https://github.com/acme/t", Optional.empty())));
        String h = Giter8Catalog.helpKnown(cfg);
        assertThat(h).contains("java-cli").contains("jk-templates").contains("acme");
    }
}
