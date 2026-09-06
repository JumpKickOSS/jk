// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.mcp.McpManifest;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
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
