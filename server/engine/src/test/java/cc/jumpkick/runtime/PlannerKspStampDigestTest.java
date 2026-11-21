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
 * edit to {@code [build] ksp-options} or a different KSP2 release with untouched sources re-runs the
 * round.
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
            null,
            null,
            List.of(),
            List.of(),
            null);

    private static final String PLAIN = """
            name    = "demo"
            group   = "com.demo"
            version = "0.1.0"
            java    = 25
            kotlin  = "2.4.10"
            """;

    @Test
    void ksp_options_and_the_kotlin_and_ksp_versions_move_the_digest(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("app"));
        Path javaHome = Path.of(System.getProperty("java.home"));

        String base =
                PlannerKsp.kspStampDigest(JkBuildParser.parse(PLAIN), LOCK, module, "2.4.10", "2.3.12", javaHome, 25);
        assertThat(PlannerKsp.kspStampDigest(
                        JkBuildParser.parse(PLAIN), LOCK, module, "2.4.10", "2.3.12", javaHome, 25))
                .isEqualTo(base);

        String withOptions = PLAIN + """

                [build]
                ksp-options = ["room.schemaLocation=schemas"]
                """;
        var optioned = JkBuildParser.parse(withOptions);
        assertThat(PlannerKsp.kspOptions(optioned, module, LOCK)).containsExactly("room.schemaLocation=schemas");
        assertThat(PlannerKsp.kspStampDigest(optioned, LOCK, module, "2.4.10", "2.3.12", javaHome, 25))
                .isNotEqualTo(base);
        assertThat(PlannerKsp.kspStampDigest(
                        JkBuildParser.parse(PLAIN), LOCK, module, "2.4.20", "2.3.12", javaHome, 25))
                .isNotEqualTo(base);
        assertThat(PlannerKsp.kspStampDigest(
                        JkBuildParser.parse(PLAIN), LOCK, module, "2.4.10", "2.3.10", javaHome, 25))
                .as("a KSP2 release change re-runs the round on an unchanged tree")
                .isNotEqualTo(base);
    }

    /**
     * The round's key names the Java level as every compile key does; its stamp, which says whether
     * this checkout's outputs are current, keeps the exact JDK.
     */
    @Test
    void the_round_key_names_the_level_and_the_stamp_the_jdk(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("app"));
        Path patchA = fakeJdk(tmp.resolve("a"), "25.0.4.1+1-LTS");
        Path patchB = fakeJdk(tmp.resolve("b"), "25.0.5+11-LTS");
        var project = JkBuildParser.parse(PLAIN);
        assertThat(PlannerKsp.roundKeyDigest(project, LOCK, module, patchA, 25))
                .isEqualTo(PlannerKsp.roundKeyDigest(project, LOCK, module, patchB, 25));
        assertThat(PlannerKsp.kspStampDigest(project, LOCK, module, "2.4.10", "2.3.12", patchA, 25))
                .isNotEqualTo(PlannerKsp.kspStampDigest(project, LOCK, module, "2.4.10", "2.3.12", patchB, 25));
    }

    private static Path fakeJdk(Path home, String runtime) throws Exception {
        Files.createDirectories(home.resolve("bin"));
        String version = runtime.substring(0, runtime.indexOf('+'));
        Files.writeString(
                home.resolve("release"),
                "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"" + version + "\"\nJAVA_RUNTIME_VERSION=\"" + runtime
                        + "\"\n");
        return home;
    }
}
