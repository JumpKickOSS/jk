// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The lock's {@code [node]} table: written, read back, scanned and checked for staleness. */
class LockfileNodePinTest {

    private static final NodePin PIN = new NodePin(
            "24.21.0",
            "11.6.0",
            "pnpm@10.18.1",
            Map.of("linux-x64", "a".repeat(64), "darwin-arm64", "b".repeat(64), "win-x64", "c".repeat(64)));

    @Test
    void a_node_pin_round_trips_through_the_lock_row_grammar() {
        String text = LockfileWriter.render(Lockfile.empty("1.0.0").withNode(PIN));

        assertThat(text)
                .contains("\n[node]\nversion = \"24.21.0\"\nnpm = \"11.6.0\"\npackage-manager = \"pnpm@10.18.1\"\n")
                .contains("sha256.darwin-arm64 = \"" + "b".repeat(64) + "\"\n");
        // The row grammar reads it itself: a lock never falls back to the slow parser for [node].
        assertThat(LockRowParser.parse(text).isTable("node")).isTrue();
        assertThat(LockfileReader.parse(text).node()).isEqualTo(PIN);
    }

    @Test
    void npm_is_written_as_no_package_manager() {
        NodePin npm = new NodePin("24.21.0", "11.6.0", null, Map.of("linux-x64", "a".repeat(64)));
        String text = LockfileWriter.render(Lockfile.empty("1.0.0").withNode(npm));

        assertThat(text).doesNotContain("package-manager");
        assertThat(LockfileReader.parse(text).node()).isEqualTo(npm);
        assertThat(npm.token()).isEqualTo("node:24.21.0");
        assertThat(PIN.token()).isEqualTo("node:24.21.0+pnpm@10.18.1");
    }

    @Test
    void an_unknown_or_versionless_node_table_is_refused() {
        String base = LockfileWriter.render(Lockfile.empty("1.0.0"));

        assertThatThrownBy(() -> LockfileReader.parse(base + "\n[node]\nversion = \"24.21.0\"\ncorepack = \"1\"\n"))
                .hasMessageContaining("[node] has unknown key(s) [corepack]");
        assertThatThrownBy(() -> LockfileReader.parse(base + "\n[node]\nnpm = \"11.6.0\"\n"))
                .hasMessageContaining("[node] names no version");
    }

    @Test
    void the_line_scan_reads_the_node_version(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\n");
        LockfileWriter.write(Lockfile.empty("1.0.0").withNode(PIN), dir.resolve("jk-lock.toml"));

        assertThat(ToolchainPins.scan(dir).node()).isEqualTo("24.21.0");
        assertThat(ToolchainPins.scan(dir).nodePackageManager()).isEqualTo("pnpm@10.18.1");
    }

    @Test
    void a_lock_without_node_for_a_project_that_declares_it_is_stale(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\nnode = 24\n");
        Path lock = dir.resolve("jk-lock.toml");

        LockfileWriter.write(Lockfile.empty("1.0.0"), lock);
        assertThat(LockFreshness.isStale(dir, lock)).as("no [node] pin").isTrue();

        LockfileWriter.write(Lockfile.empty("1.0.0").withNode(PIN), lock);
        assertThat(LockFreshness.isStale(dir, lock)).as("pinned").isFalse();
    }
}
