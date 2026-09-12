// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.base.GuardSuiteLibrary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace that builds the guard-test library itself locks it by module path, not by the digest
 * of whatever jar is installed, and a second lock rewrites nothing. Deps resolve from a {@code
 * file://} repo; nothing touches the network.
 */
@Tag("integration")
class WorkspaceGuardLibraryLockTest {

    /** Minimal empty-zip bytes — a valid jar as far as fetching and hashing are concerned. */
    private static final byte[] EMPTY_ZIP = {
        0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    @Test
    void the_library_module_is_locked_by_path_and_a_relock_is_a_no_op(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        // The engine adds the test-runner infra to every module's closure — stub it.
        stubArtifact(repo, "org.junit.platform", "junit-platform-launcher", "1.10.0");
        stubArtifact(repo, "org.junit.jupiter", "junit-jupiter", "5.10.0");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["app", "guards"]
                """);
        Files.createDirectories(ws.resolve("app/src/main/java"));
        Files.createDirectories(ws.resolve("app/src/guard/java"));
        Files.writeString(ws.resolve("app/jk.toml"), "group = \"com.example\"\nname = \"app\"\nversion = \"1.0.0\"\n");
        Files.writeString(ws.resolve("app/src/main/java/App.java"), "class App {}\n");
        Files.writeString(ws.resolve("app/src/guard/java/HouseRules.java"), "class HouseRules {}\n");
        Files.createDirectories(ws.resolve("guards/src/main/java"));
        Files.writeString(
                ws.resolve("guards/jk.toml"),
                "group = \"cc.jumpkick\"\nname = \"" + GuardSuiteLibrary.ARTIFACT + "\"\nversion = \"1.0.0\"\n");
        Files.writeString(ws.resolve("guards/src/main/java/Guard.java"), "class Guard {}\n");

        Path cache = tmp.resolve("cache");
        assertThat(lock(ws, cache, repo).success()).isTrue();
        Path lockFile = LockPaths.lockFile(ws);
        byte[] first = Files.readAllBytes(lockFile);
        Lockfile written = LockfileReader.read(lockFile);
        Lockfile.PluginEntry library = written.plugins().stream()
                .filter(e -> e.coordinate().equals(GuardSuiteLibrary.COORDINATE))
                .findFirst()
                .orElseThrow();
        assertThat(library.path()).isEqualTo("guards");
        assertThat(library.version()).isEqualTo("1.0.0");
        assertThat(library.checksum()).isNull();

        LockfileReader.clearCache();
        assertThat(lock(ws, cache, repo).success()).isTrue();
        assertThat(Files.readAllBytes(lockFile))
                .as("a re-lock rewrites nothing")
                .isEqualTo(first);
    }

    private static BuildPlanResult lock(Path ws, Path cache, Path repo) throws Exception {
        return LockPlans.lockBuildPlan(
                        ws,
                        JkBuildParser.parse(ws.resolve("jk.toml")),
                        cache,
                        repo.toUri(),
                        List.of(),
                        true,
                        false,
                        ResolveObserver.NOOP,
                        null)
                .run();
    }

    private static void stubArtifact(Path repo, String group, String artifact, String version) throws Exception {
        Path vDir = Files.createDirectories(repo.resolve(group.replace('.', '/') + "/" + artifact + "/" + version));
        Files.write(vDir.resolve(artifact + "-" + version + ".jar"), EMPTY_ZIP);
        Files.writeString(requireNonNull(vDir.getParent()).resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <release>%s</release>
                    <versions><version>%s</version></versions>
                  </versioning>
                </metadata>
                """.formatted(
                        group, artifact, version, version));
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
}
