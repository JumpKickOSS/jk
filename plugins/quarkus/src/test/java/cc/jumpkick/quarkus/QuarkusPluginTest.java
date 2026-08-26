// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The packager half: augment-output consumption, produced-path declarations, package type. */
class QuarkusPluginTest {

    @Test
    void normalize_package_type_accepts_aliases_and_rejects_garbage() throws IOException {
        assertThat(QuarkusPlugin.normalizePackageType(null)).isEqualTo("fast-jar");
        assertThat(QuarkusPlugin.normalizePackageType(" Fast ")).isEqualTo("fast-jar");
        assertThat(QuarkusPlugin.normalizePackageType("uberjar")).isEqualTo("uber-jar");
        assertThat(QuarkusPlugin.normalizePackageType("fat-jar")).isEqualTo("uber-jar");
        assertThatThrownBy(() -> QuarkusPlugin.normalizePackageType("war")).isInstanceOf(IOException.class);
    }

    @Test
    void fast_jar_promotes_siblings_and_declares_them_produced(@TempDir Path dir) throws Exception {
        Path augment = dir.resolve("augment/quarkus-app");
        Files.createDirectories(augment.resolve("lib/main"));
        Files.createDirectories(augment.resolve("app"));
        Files.createDirectories(augment.resolve("quarkus"));
        Files.writeString(augment.resolve("quarkus-run.jar"), "RUN");
        Files.writeString(augment.resolve("lib/main/dep.jar"), "DEP");
        Files.writeString(augment.resolve("app/app.jar"), "APP");
        Files.writeString(augment.resolve("quarkus/generated-bytecode.jar"), "GEN");

        Path outJar = dir.resolve("target/hello.jar");
        FakeBuildIo io = fake(dir, outJar, dir.resolve("augment"));
        QuarkusPlugin.produceFastJar(io);

        assertThat(outJar).hasContent("RUN");
        assertThat(outJar.getParent().resolve("lib/main/dep.jar")).exists();
        assertThat(outJar.getParent().resolve("quarkus-app/quarkus-run.jar")).exists();
        // the multi-file layout must be declared so the packaging cache stores it whole.
        assertThat(io.produced())
                .contains(
                        outJar.getParent().resolve("lib"),
                        outJar.getParent().resolve("app"),
                        outJar.getParent().resolve("quarkus"),
                        outJar.getParent().resolve("quarkus-app"));
    }

    @Test
    void uber_jar_wins_and_needs_no_siblings(@TempDir Path dir) throws Exception {
        Path augment = dir.resolve("augment");
        Files.createDirectories(augment);
        Files.writeString(augment.resolve("quarkus-uber.jar"), "UBER");

        Path outJar = dir.resolve("target/hello.jar");
        FakeBuildIo io = fake(dir, outJar, augment);
        QuarkusPlugin.produceFastJar(io);

        assertThat(outJar).hasContent("UBER");
        assertThat(io.produced()).isEmpty();
    }

    @Test
    void missing_augment_output_fails_instead_of_falling_back(@TempDir Path dir) throws Exception {
        Path augment = dir.resolve("augment");
        Files.createDirectories(augment); // exists but empty — no runner, no layout
        FakeBuildIo io = fake(dir, dir.resolve("target/hello.jar"), augment);
        assertThatThrownBy(() -> QuarkusPlugin.produceFastJar(io))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("quarkus-run.jar");
    }

    /**
     * The shared engine fake, wired the way the augment step leaves things: the artifact lands
     * where {@code artifact} says, and {@code AUGMENT_STEP}'s output root is the tree the test
     * built. {@code moduleDir}/{@code classesDir} follow the artifact, as they do for a Quarkus
     * app whose fast-jar layout is promoted next to the runner.
     */
    private static FakeBuildIo fake(Path root, Path artifact, Path augmentRoot) throws IOException {
        return new FakeBuildIo(root, "quarkus")
                .offline(false)
                .project("g", "hello", "1.0", null)
                .artifactPath(artifact)
                .classesDir(artifact.getParent())
                .moduleDir(artifact.getParent())
                .step(QuarkusPlugin.AUGMENT_STEP, augmentRoot);
    }
}
