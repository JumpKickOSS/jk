// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.Lockfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The KSP round's freshness stamp records a digest of its processor options and toolchain, so an
 * edit to {@code [build] ksp-options} with untouched sources re-runs the round.
 */
class PlannerKspStampDigestTest {

    private static final Lockfile LOCK = new Lockfile(
            Lockfile.CURRENT_VERSION,
            "test",
            Lockfile.RESOLUTION_ALGORITHM,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            null,
            null,
            null);

    private static final String PLAIN = """
            name    = "demo"
            group   = "com.demo"
            version = "0.1.0"
            java    = 25
            kotlin  = "2.4.10"
            """;

    @Test
    void ksp_options_and_the_kotlin_version_move_the_digest(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("app"));
        Path javaHome = Path.of(System.getProperty("java.home"));

        String base = PlannerKsp.kspStampDigest(JkBuildParser.parse(PLAIN), LOCK, module, "2.4.10", javaHome, 25);
        assertThat(PlannerKsp.kspStampDigest(JkBuildParser.parse(PLAIN), LOCK, module, "2.4.10", javaHome, 25))
                .isEqualTo(base);

        String withOptions = PLAIN + """

                [build]
                ksp-options = ["room.schemaLocation=schemas"]
                """;
        var optioned = JkBuildParser.parse(withOptions);
        assertThat(PlannerKsp.kspOptions(optioned, module, LOCK)).containsExactly("room.schemaLocation=schemas");
        assertThat(PlannerKsp.kspStampDigest(optioned, LOCK, module, "2.4.10", javaHome, 25))
                .isNotEqualTo(base);
        assertThat(PlannerKsp.kspStampDigest(JkBuildParser.parse(PLAIN), LOCK, module, "2.4.20", javaHome, 25))
                .isNotEqualTo(base);
    }
}
