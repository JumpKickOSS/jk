// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The agent report of a failed resolve that carries a manifest location. */
class JkResultsAgentResolveTest {

    @Test
    void a_located_resolve_failure_names_the_failure_and_what_to_do_not_the_banner(@TempDir Path dir) throws Exception {
        Path manifest = Files.writeString(
                dir.resolve("jk.toml"), "group = \"g\"\nname = \"a\"\n\n[dependencies]\nlib = { group = \"g.x\" }\n");
        BuildRecord.Diag refused = new BuildRecord.Diag(
                "error",
                dir.toString(),
                "resolve-deps",
                "verbatim",
                "‼ Cannot resolve dependencies:\n  │ Package g.x:lib was not found in any repository\n"
                        + "  │ repository `private` skipped (HTTP 401: it asks for credentials)\n",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                manifest.toString(),
                5,
                0,
                0,
                List.of(),
                0);
        BuildRecord r =
                JkResultsAgentTest.record(dir.toString(), "lock", false, false, 90, null, List.of(refused), List.of());
        assertThat(JkResultsAgent.render(r))
                .contains("E jk.toml:5 │ Package g.x:lib was not found in any repository\n")
                .contains("  5|\tlib = { group = \"g.x\" }\n")
                .contains("HTTP 401")
                .doesNotContain("Cannot resolve dependencies:");
    }
}
