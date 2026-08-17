// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan-time native weight must reserve a full wall when main sources are dirty — even if an old
 * native binary is still newer than the pre-build jar (the failure mode that parked the bar at
 * 100% for the entire Graal run).
 */
class NativeWeightDirtyJarTest {

    @Test
    void nativeWeight_over_reserves_when_main_sources_are_dirty(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                name = "demo"
                group = "t"
                version = "0.0.1"
                java = 25

                [native]
                enabled = "always"
                """);
        Path mainJava = dir.resolve("src/main/java/Demo.java");
        Files.createDirectories(mainJava.getParent());
        Files.writeString(mainJava, "class Demo { public static void main(String[] a) {} }\n");
        // Stale jar + "fresh" native binary (newer than jar) — classic false skip.
        Path target = dir.resolve("target");
        Files.createDirectories(target);
        Path jar = target.resolve("demo-0.0.1.jar");
        Path nativeBin = target.resolve("demo");
        Files.writeString(jar, "old-jar");
        Thread.sleep(20);
        Files.writeString(nativeBin, "old-native");
        // No classes stamp / stamp older than source → mainJarWillChange
        assertThat(EffortWeights.mainJarWillChange(dir)).isTrue();
        int w = EffortWeights.nativeWeight(dir);
        assertThat(w).isGreaterThan(EffortWeights.TOKEN);
        // Cold floor is NATIVE_RUN (600) when no metrics; learned path may differ but must be heavy.
        assertThat(w).isGreaterThanOrEqualTo(100);
    }

    @Test
    void nativeRunWeight_is_always_positive() {
        assertThat(EffortWeights.nativeRunWeight(Path.of("/nonexistent-module-" + System.nanoTime())))
                .isGreaterThan(EffortWeights.TOKEN);
    }

    @Test
    void jar_dirty_implies_native_and_oci_dirty(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                name = "demo"
                group = "t"
                version = "0.0.1"
                java = 25

                [native]
                enabled = "always"
                """);
        Path mainJava = dir.resolve("src/main/java/Demo.java");
        Files.createDirectories(mainJava.getParent());
        Files.writeString(mainJava, "class Demo { public static void main(String[] a) {} }\n");
        // No classes stamp → jar dirty
        assertThat(EffortWeights.jarWillChange(dir)).isTrue();
        assertThat(EffortWeights.nativeWillChange(dir)).isTrue();
        assertThat(EffortWeights.ociWillChange(dir)).isTrue();
        assertThat(EffortWeights.nativeWeight(dir)).isGreaterThan(EffortWeights.TOKEN);
    }

    @Test
    void overReserveTails_forces_full_native_even_when_binary_looks_fresh(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                name = "demo"
                group = "t"
                version = "0.0.1"
                java = 25

                [native]
                enabled = "always"
                """);
        Path mainJava = dir.resolve("src/main/java/Demo.java");
        Files.createDirectories(mainJava.getParent());
        Files.writeString(mainJava, "class Demo { public static void main(String[] a) {} }\n");
        // Stamp classes as fresh so mainJarWillChange is false — only over-reserve should fire.
        Path classes = dir.resolve("target/classes");
        Files.createDirectories(classes);
        Files.writeString(classes.resolve(".jstamp"), "ok");
        Path jar = dir.resolve("target/demo-0.0.1.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");
        Thread.sleep(20);
        Files.writeString(dir.resolve("target/demo"), "native-bin");

        // Without over-reserve, a "fresh" binary can collapse to SKIP/TOKEN.
        // With over-reserve (dirty prepare), full learned/cold wall is reserved up front.
        int reserved = EffortWeights.withOverReserveTails(() -> EffortWeights.nativeWeight(dir));
        assertThat(reserved).isGreaterThanOrEqualTo(100);

        // The production path (BuildPlan.estimatedTotalWeight) evaluates weight suppliers on
        // JkThreads.io() workers — the flag must survive that hop.
        int reservedViaPool = EffortWeights.withOverReserveTails(() -> CompletableFuture.supplyAsync(
                        () -> EffortWeights.nativeWeight(dir), cc.jumpkick.run.JkThreads.io())
                .join());
        assertThat(reservedViaPool).isGreaterThanOrEqualTo(100);

        // And a worker outside the scope must NOT see the flag (no leak into pooled threads).
        boolean leaked = CompletableFuture.supplyAsync(EffortWeights::overReserveTails, cc.jumpkick.run.JkThreads.io())
                .join();
        assertThat(leaked).isFalse();
    }
}
