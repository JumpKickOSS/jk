// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.testing.SourceText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A test using the persistent spike cache roots its project in a per-method {@code @TempDir}.
 *
 * <p>That cache is keyed by action id, which includes the project's absolute path. A test that
 * reuses a fixed root replays the stored record and the code under test never executes — a green
 * run that proves nothing. A fresh {@code @TempDir} qualifies the id afresh.
 *
 * <p>Detection-shape honesty: the presence of {@code @TempDir} does not prove the project
 * <em>root</em> lives inside it. This closes the cheap regression — a spike test born with no
 * {@code @TempDir} at all — not every replay. The strong check remains the throw-probe: make the
 * code under test throw, and a green run is a replay.
 */
class SpikeCacheTempDirTest {

    private static final String MARKER = "android-spike-cache";

    /**
     * The one exemption, as a path rather than a name matched in a regex: moving or renaming the
     * file fails this test loudly instead of silently narrowing the exemption to nothing.
     *
     * <p>{@code NiaScratchTest} builds an external checkout (env-gated), so its module roots are
     * that clone's own directories.
     */
    private static final String EXEMPT = "server/engine/src/test/java/cc/jumpkick/runtime/NiaScratchTest.java";

    @Test
    void every_spike_cache_test_takes_a_temp_dir() throws IOException {
        Path root = RepoRoot.find(SpikeCacheTempDirTest.class);
        RepoRoot.file(SpikeCacheTempDirTest.class, EXEMPT); // asserts the exemption still exists

        List<Path> naming = new ArrayList<>();
        for (Path f : SourceText.javaUnder(root.resolve("server/engine/src/test/java"))) {
            if (Files.readString(f).contains(MARKER)) naming.add(f);
        }
        assertThat(naming.size())
                .as(
                        "files naming \"%s\" — 18 when this was measured; fewer means a renamed marker or"
                                + " a moved tree, not a clean population",
                        MARKER)
                .isGreaterThanOrEqualTo(10);

        List<String> missing = new ArrayList<>();
        for (Path f : naming) {
            String rel = SourceText.rel(root, f);
            if (rel.equals(EXEMPT)) continue;
            if (!Files.readString(f).contains("@TempDir")) missing.add(rel);
        }
        assertThat(missing)
                .as("without a per-method @TempDir the action cache replays the stored record and the"
                        + " code under test never runs")
                .isEmpty();
    }
}
