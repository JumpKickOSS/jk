// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
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
    void failure_text_keeps_the_forks_own_header_lines_above_its_last_sixty() {
        StringBuilder out = new StringBuilder();
        out.append("jk-quarkus-augment: remote repositories: central=https://repo1.maven.org/maven2/\n");
        out.append("jk-quarkus-augment: locked runtime closure=412 jars\n");
        for (int i = 0; i < 200; i++)
            out.append("[io.quarkus.deployment] noise line ").append(i).append('\n');
        out.append("Caused by: java.lang.IllegalStateException: no such artifact\n");

        String text = QuarkusPlugin.failureText("jk-quarkus-augment", out.toString());
        String[] lines = text.split("\n");

        assertThat(lines[0])
                .isEqualTo("jk-quarkus-augment: remote repositories: central=https://repo1.maven.org/maven2/");
        assertThat(lines[1]).isEqualTo("jk-quarkus-augment: locked runtime closure=412 jars");
        assertThat(text).contains("Caused by: java.lang.IllegalStateException: no such artifact");
        assertThat(text).doesNotContain("noise line 0\n").contains("noise line 199");
        // the header rides once: a header line already inside the tail window is not repeated
        String shortRun = "jk-quarkus-augment: remote repositories: none\nboom\n";
        assertThat(QuarkusPlugin.failureText("jk-quarkus-augment", shortRun))
                .isEqualTo("jk-quarkus-augment: remote repositories: none\nboom");
        assertThat(QuarkusPlugin.failureText("jk-quarkus-augment", "")).isEmpty();
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

        Path target = dir.resolve("target");
        Path outJar = target.resolve("hello.jar");
        FakeBuildIo io = fake(dir, outJar, dir.resolve("augment"));
        QuarkusPlugin.produceFastJar(io);

        assertThat(outJar).hasContent("RUN");
        assertThat(target.resolve("lib/main/dep.jar")).exists();
        assertThat(target.resolve("quarkus-app/quarkus-run.jar")).exists();
        // the multi-file layout must be declared so the packaging cache stores it whole.
        assertThat(io.produced())
                .contains(
                        target.resolve("lib"),
                        target.resolve("app"),
                        target.resolve("quarkus"),
                        target.resolve("quarkus-app"));
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
        Path artifactDir = Objects.requireNonNull(artifact.getParent());
        return new FakeBuildIo(root, "quarkus")
                .offline(false)
                .project("g", "hello", "1.0", null)
                .artifactPath(artifact)
                .classesDir(artifactDir)
                .moduleDir(artifactDir)
                .step(QuarkusPlugin.AUGMENT_STEP, augmentRoot);
    }
}
