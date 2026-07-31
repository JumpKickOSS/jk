// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.ProjectFacts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        FakeIo io = new FakeIo(outJar, dir.resolve("augment"));
        QuarkusPlugin.produceFastJar(io);

        assertThat(outJar).hasContent("RUN");
        assertThat(outJar.getParent().resolve("lib/main/dep.jar")).exists();
        assertThat(outJar.getParent().resolve("quarkus-app/quarkus-run.jar")).exists();
        // the multi-file layout must be declared so the packaging cache stores it whole.
        assertThat(io.produced)
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
        FakeIo io = new FakeIo(outJar, augment);
        QuarkusPlugin.produceFastJar(io);

        assertThat(outJar).hasContent("UBER");
        assertThat(io.produced).isEmpty();
    }

    @Test
    void missing_augment_output_fails_instead_of_falling_back(@TempDir Path dir) throws Exception {
        Path augment = dir.resolve("augment");
        Files.createDirectories(augment); // exists but empty — no runner, no layout
        FakeIo io = new FakeIo(dir.resolve("target/hello.jar"), augment);
        assertThatThrownBy(() -> QuarkusPlugin.produceFastJar(io))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("quarkus-run.jar");
    }

    private static final class FakeIo implements PackageIo {
        final Path artifact;
        final Path augmentRoot;
        final List<Path> produced = new ArrayList<>();

        FakeIo(Path artifact, Path augmentRoot) {
            this.artifact = artifact;
            this.augmentRoot = augmentRoot;
        }

        @Override
        public Path classesDir() {
            return artifact.getParent();
        }

        @Override
        public Path moduleDir() {
            return artifact.getParent();
        }

        @Override
        public List<RuntimeEntry> runtimeEntries() {
            return List.of();
        }

        @Override
        public PluginConfig config() {
            return new PluginConfig("quarkus", Map.of());
        }

        @Override
        public ProjectFacts project() {
            return new ProjectFacts("g", "hello", "1.0", 21, null, false, false, Map.of());
        }

        @Override
        public Optional<Path> stepOutput(String step) {
            return Optional.of(augmentRoot);
        }

        @Override
        public Optional<Path> extra(String name) {
            return Optional.empty();
        }

        @Override
        public Path artifactPath() {
            return artifact;
        }

        @Override
        public Path javaHome() {
            return Path.of(System.getProperty("java.home"));
        }

        @Override
        public void label(String text) {}

        @Override
        public void produced(Path path) {
            produced.add(path);
        }
    }
}
