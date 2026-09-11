// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.rules.GuardPacks;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.RuleSource;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.GuardsConfig;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlanResult;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A rule pack named by {@code [guards] extends} is pinned by {@code jk lock} like a plugin, its jar
 * lands in the store, and the loader unpacks and reads it from the pin alone — no repository is
 * consulted after the lock. Deps resolve from a {@code file://} repo; nothing touches the network.
 */
@Tag("integration")
class GuardPackPinTest {

    private static final String FRAGMENT = """
            [guards.no-system-out]
            kind       = "forbid"
            signatures = ["java.lang.System#out"]
            owner      = "com.acme.Log"
            instead    = "Log.info"
            why        = "stdout is not a log"
            """;

    @Test
    void a_declared_pack_is_pinned_and_unpacked_by_lock_and_read_without_the_repository(@TempDir Path tmp)
            throws Exception {
        Path repo = tmp.resolve("repo");
        packArtifact(repo, "com.acme", "house-rules", "2.0.0");
        // The engine adds the test-runner infra to every module's closure — stub it.
        stubArtifact(repo, "org.junit.platform", "junit-platform-launcher", "1.10.0");
        stubArtifact(repo, "org.junit.jupiter", "junit-jupiter", "5.10.0");
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.example"
                name  = "demo"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);
        Files.writeString(project.resolve(GuardsPresence.RULES_FILE), """
                [guards]
                extends = ["com.acme:house-rules:2.0.0"]

                [guards.one-owner]
                kind = "split-package"
                why  = "one module per package"
                """);
        Path cache = tmp.resolve("cache");
        BuildPlanResult lock = LockPlans.lockBuildPlan(
                        project,
                        JkBuildParser.parse(project.resolve("jk.toml")),
                        cache,
                        repo.toUri(),
                        List.of(),
                        true,
                        false,
                        ResolveObserver.NOOP,
                        null)
                .run();
        assertThat(lock.success()).as("lock errors: " + lock.errors()).isTrue();
        Lockfile written = LockfileReader.read(LockPaths.lockFile(project));
        assertThat(written.plugins())
                .as("the pack pins like a plugin")
                .extracting(Lockfile.PluginEntry::coordinate, Lockfile.PluginEntry::version)
                .contains(tuple("com.acme:house-rules", "2.0.0"));

        // The lock unpacked the pack where the loader reads it: the repository is not consulted again.
        GuardPacks.Coordinate c = Objects.requireNonNull(GuardPacks.Coordinate.parse("com.acme:house-rules:2.0.0"));
        assertThat(GuardPacks.fragment(GuardPacks.unpackedDir(project, c))).exists();
        deleteTree(repo);
        LoadResult load = GuardRules.load(
                project, GuardsConfig.ABSENT, JkStores.storeCas().root());
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        assertThat(load.rules().ids()).containsExactly("no-system-out", "one-owner");
        assertThat(load.rules().rule("no-system-out").orElseThrow().source().layer())
                .isEqualTo(RuleSource.Layer.PACK);
        assertThat(GuardPacks.unpackedDir(project, c).resolve("guard-fixtures/no-system-out/Bad.java"))
                .exists();
    }

    private static void metadata(Path dir, String group, String artifact, String version) throws Exception {
        Files.writeString(dir.resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <release>%s</release>
                    <versions><version>%s</version></versions>
                  </versioning>
                </metadata>
                """.formatted(group, artifact, version, version));
    }

    private static void packArtifact(Path repo, String group, String artifact, String version) throws Exception {
        Path vDir = Files.createDirectories(repo.resolve(group.replace('.', '/') + "/" + artifact + "/" + version));
        metadata(requireNonNull(vDir.getParent()), group, artifact, version);
        Path jar = vDir.resolve(artifact + "-" + version + ".jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out)) {
            jos.putNextEntry(new JarEntry("jk-guards.toml"));
            jos.write(FRAGMENT.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("guard-fixtures/no-system-out/Bad.java"));
            jos.write("class Bad {}\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        Files.writeString(vDir.resolve(artifact + "-" + version + ".pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version));
    }

    /** Minimal empty-zip bytes — a valid jar as far as fetching and hashing are concerned. */
    private static final byte[] EMPTY_ZIP = {
        0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    private static void stubArtifact(Path repo, String group, String artifact, String version) throws Exception {
        Path vDir = Files.createDirectories(repo.resolve(group.replace('.', '/') + "/" + artifact + "/" + version));
        Files.write(vDir.resolve(artifact + "-" + version + ".jar"), EMPTY_ZIP);
        metadata(requireNonNull(vDir.getParent()), group, artifact, version);
        Files.writeString(vDir.resolve(artifact + "-" + version + ".pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version));
    }

    private static void deleteTree(Path dir) throws Exception {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
