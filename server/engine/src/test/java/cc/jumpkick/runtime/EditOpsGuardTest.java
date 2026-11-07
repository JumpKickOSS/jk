// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.mcp.McpManifest;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.runtime.base.EditOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A depend rule refuses a manifest edit before it is written — CLI and MCP paths alike. */
class EditOpsGuardTest {

    private static Path project(Path dir, boolean withRules) throws Exception {
        Path p = Files.createDirectories(dir.resolve("p"));
        Files.writeString(p.resolve(ManifestPaths.MANIFEST), """
                name = "p"
                group = "g"
                version = "1"

                [dependencies]
                """);
        if (withRules) {
            Files.writeString(p.resolve(GuardsPresence.RULES_FILE), """
                    [guards.no-junit4]
                    kind = "depend"
                    ban = ["junit:junit"]
                    instead = "org.junit.jupiter:junit-jupiter"
                    why = "one test framework"
                    """);
        }
        return p;
    }

    @Test
    void jk_add_of_a_banned_coordinate_is_refused_and_the_file_untouched(@TempDir Path dir) throws Exception {
        Path p = project(dir, true);
        Path manifest = p.resolve(ManifestPaths.MANIFEST);
        String before = Files.readString(manifest);
        EditOps.Result r =
                EditOps.apply(manifest, "add-dependency", List.of("main", "junit", "junit", "junit", "=4.13.2"));
        assertThat(r.changed()).isFalse();
        assertThat(r.error())
                .startsWith("GUARD no-junit4  refused this edit")
                .contains("Instead:  org.junit.jupiter:junit-jupiter")
                .contains("no --force");
        assertThat(Files.readString(manifest)).isEqualTo(before);

        EditOps.Result ok = EditOps.apply(
                manifest, "add-dependency", List.of("main", "guava", "com.google.guava", "guava", "=33.4.0-jre"));
        assertThat(ok.changed()).isTrue();
        assertThat(ok.error()).isNull();
    }

    /**
     * A workspace whose {@code kit} declares junit in main scope, so the lock's junit row carries
     * {@code main}, and a rule scoped to {@code cli} that bans test libraries from its runtime tables.
     */
    private static Path scopedWorkspace(Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Files.writeString(root.resolve(ManifestPaths.MANIFEST), """
                group = "g"
                name = "ws"
                version = "1"

                [workspace]
                modules = ["cli", "kit"]
                """);
        Files.createDirectories(root.resolve("cli"));
        Files.writeString(root.resolve("cli/" + ManifestPaths.MANIFEST), """
                name = "cli"
                group = "g"
                version = "1"

                [test-dependencies]
                junit = { group = "org.junit.jupiter", name = "junit-jupiter-api", version = "=6.1.3" }
                """);
        Files.createDirectories(root.resolve("kit"));
        Files.writeString(root.resolve("kit/" + ManifestPaths.MANIFEST), """
                name = "kit"
                group = "g"
                version = "1"

                [dependencies]
                junit = { group = "org.junit.jupiter", name = "junit-jupiter-api", version = "=6.1.3" }
                """);
        Files.writeString(root.resolve(ManifestPaths.LOCK), """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"
                jk-min = "0.12.0"

                [[artifact]]
                name     = "org.junit.jupiter:junit-jupiter-api:jar:"
                version  = "6.1.3"
                source   = "central+https://repo.example/"
                scopes   = ["main", "test"]
                """);
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.cli-runtime-classpath]
                kind    = "depend"
                scope   = ["cli"]
                scopes  = ["dependencies", "runtime-dependencies", "provided-dependencies"]
                ban     = ["org.junit.jupiter:*", "com.microsoft.playwright:*"]
                instead = "test-dependencies"
                why     = "the cli runtime carries no test library"
                """);
        return root.resolve("cli/" + ManifestPaths.MANIFEST);
    }

    @Test
    void a_test_scope_add_passes_the_runtime_rule_a_main_scope_add_of_the_same_library_is_refused_by(@TempDir Path dir)
            throws Exception {
        Path manifest = scopedWorkspace(dir);
        String before = Files.readString(manifest);
        List<String> playwright = List.of("playwright", "com.microsoft.playwright", "playwright", "=1.57.0");

        EditOps.Result main = EditOps.apply(manifest, "add-dependency", with("main", playwright));
        assertThat(main.changed()).isFalse();
        assertThat(main.error())
                .startsWith("GUARD cli-runtime-classpath  refused this edit")
                .contains("com.microsoft.playwright:playwright")
                .as("the lock's junit row is another module's; a rule scoped to cli reads cli's manifest")
                .doesNotContain("junit");
        assertThat(Files.readString(manifest)).isEqualTo(before);

        EditOps.Result test = EditOps.apply(manifest, "add-dependency", with("test", playwright));
        assertThat(test.error()).isNull();
        assertThat(test.changed()).isTrue();
        // The row lands either way; whether it is the short name or full coordinates depends on
        // whether the home holds the global library catalog, which another test may have fetched.
        assertThat(Files.readString(manifest)).containsPattern("(?m)^playwright\\s*=");
    }

    private static List<String> with(String scope, List<String> rest) {
        List<String> out = new ArrayList<>();
        out.add(scope);
        out.addAll(rest);
        return out;
    }

    @Test
    void without_guards_the_edit_takes_the_old_path(@TempDir Path dir) throws Exception {
        Path p = project(dir, false);
        EditOps.Result r = EditOps.apply(
                p.resolve(ManifestPaths.MANIFEST),
                "add-dependency",
                List.of("main", "junit", "junit", "junit", "=4.13.2"));
        assertThat(r.changed()).isTrue();
    }

    @Test
    void mcp_deps_returns_the_refusal_in_notes_and_never_applies(@TempDir Path dir) throws Exception {
        Path p = project(dir, true);
        Map<String, Object> out = McpManifest.deps(p.toString(), "add", List.of("junit:junit:4.13.2"), "main", true);
        assertThat(out.get("changed")).isEqualTo(false);
        assertThat(out.get("applied")).isEqualTo(false);
        assertThat(String.valueOf(out.get("notes"))).contains("GUARD no-junit4");
        assertThat(Files.readString(p.resolve(ManifestPaths.MANIFEST))).doesNotContain("junit");
    }
}
