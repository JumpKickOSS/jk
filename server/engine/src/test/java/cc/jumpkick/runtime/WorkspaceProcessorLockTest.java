// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlanResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lock plan and the lock flow solve a workspace from the same merged manifest: a member's
 * {@code [processor-dependencies]} are pinned whichever of them locks the root. Deps resolve from a
 * {@code file://} repo; nothing touches the network.
 */
@Tag("integration")
class WorkspaceProcessorLockTest {

    /** Minimal empty-zip bytes — a valid jar as far as fetching and hashing are concerned. */
    private static final byte[] EMPTY_ZIP = {
        0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    @TempDir
    Path tmp;

    @Test
    void the_lock_plan_pins_a_member_s_processor_dependencies() throws Exception {
        Path ws = workspace();
        BuildPlanResult result = lockPlan(ws);
        assertThat(result.success()).as("lock: %s", result.errors()).isTrue();
        assertThat(packages(LockfileReader.read(LockPaths.lockFile(ws)))).contains("com.acme:proc", "com.acme:util");
    }

    @Test
    void the_lock_plan_writes_what_the_lock_flow_writes() throws Exception {
        Path ws = workspace();
        LockFlow.Result flow = LockFlow.run(ws, tmp.resolve("cache"), List.of(), false, repo().toUri());
        assertThat(flow.status()).as("lock flow: %s", flow.error()).isZero();
        Lockfile byFlow = LockfileReader.read(LockPaths.lockFile(ws));

        Files.delete(LockPaths.lockFile(ws));
        LockfileReader.clearCache();
        BuildPlanResult result = lockPlan(ws);
        assertThat(result.success()).as("lock: %s", result.errors()).isTrue();
        assertThat(packages(LockfileReader.read(LockPaths.lockFile(ws)))).isEqualTo(packages(byFlow));
    }

    private BuildPlanResult lockPlan(Path ws) throws Exception {
        return LockPlans.lockBuildPlan(
                        ws, tmp.resolve("cache"), repo().toUri(), List.of(), true, false, ResolveObserver.NOOP, null)
                .run();
    }

    private static List<String> packages(Lockfile lock) {
        return lock.artifacts().stream()
                .map(a -> String.join(":", List.of(a.packageKey().split(":")).subList(0, 2)))
                .sorted()
                .toList();
    }

    private Path repo() throws Exception {
        Path repo = tmp.resolve("repo");
        if (Files.isDirectory(repo)) return repo;
        stubArtifact(repo, "com.acme", "util", "1.0.0");
        stubArtifact(repo, "com.acme", "proc", "1.0.0");
        // The engine adds the test-runner infra to every module's closure — stub it.
        stubArtifact(repo, "org.junit.platform", "junit-platform-launcher", "1.10.0");
        stubArtifact(repo, "org.junit.jupiter", "junit-jupiter", "5.10.0");
        return repo;
    }

    private Path workspace() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["app"]
                """);
        Files.createDirectories(ws.resolve("app/src/main/java"));
        Files.writeString(ws.resolve("app/src/main/java/App.java"), "class App {}\n");
        Files.writeString(ws.resolve("app/jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                util = { group = "com.acme", name = "util", version = "1.0.0" }

                [processor-dependencies]
                proc = { group = "com.acme", name = "proc", version = "1.0.0" }
                """);
        return ws;
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
