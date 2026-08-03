// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.resolver.ResolveObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AutoLock#maybeReLock} inside a workspace: a stale relock triggered from one member must
 * resolve the whole workspace union into the root {@code jk-lock.toml} — never truncate it to the
 * triggering module's closure. Offline: deps resolve from a hand-written {@code file://}
 * Maven repo.
 */
@Tag("integration")
class AutoLockWorkspaceTest {

    /** Minimal empty-zip bytes — a valid jar as far as fetching/hashing is concerned. */
    private static final byte[] EMPTY_ZIP = {
        0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    private static void repoArtifact(Path repo, String group, String artifact, String version) throws Exception {
        Path dir = repo.resolve(group.replace('.', '/') + "/" + artifact);
        Path vDir = dir.resolve(version);
        Files.createDirectories(vDir);
        Files.write(vDir.resolve(artifact + "-" + version + ".jar"), EMPTY_ZIP);
        Files.writeString(vDir.resolve(artifact + "-" + version + ".pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>jar</packaging>
                </project>
                """.formatted(group, artifact, version));
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

    @Test
    void member_scoped_relock_keeps_the_sibling_union_in_the_root_lock(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        repoArtifact(repo, "com.acme", "util", "1.0.0");
        repoArtifact(repo, "com.acme", "extra", "1.0.0");
        // The engine adds the test-runner infra to every module's closure — stub it.
        repoArtifact(repo, "org.junit.platform", "junit-platform-launcher", "1.10.0");
        repoArtifact(repo, "org.junit.jupiter", "junit-jupiter", "5.10.0");

        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["core", "app"]
                """);
        Files.createDirectories(ws.resolve("core"));
        // util is declared ONLY by core: it must survive a relock triggered from app.
        Files.writeString(ws.resolve("core/jk.toml"), """
                [project]
                group = "com.example"
                name  = "core"
                version = "1.0.0"
                jdk = 25
                java = 25

                [dependencies]
                util = { group = "com.acme", name = "util", version = "1.0.0" }
                """);
        Files.createDirectories(ws.resolve("app"));
        Files.writeString(ws.resolve("app/jk.toml"), """
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);

        // An existing root lock; contents only feed conservative preferences. write() stamps a
        // live manifests-sha256, so staleness must come from a real manifest edit — mtimes are
        // irrelevant since the invisible-freshen change (LockFreshness digest regime).
        Path rootLock = cc.jumpkick.lock.LockPaths.lockFile(ws);
        LockfileWriter.write(new Lockfile(1, "test", "jk-test", List.of()), rootLock);
        // Now app grows a dep: the workspace digest no longer matches the stamp → stale.
        Files.writeString(ws.resolve("app/jk.toml"), """
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk = 25
                java = 25

                [dependencies]
                extra = { group = "com.acme", name = "extra", version = "1.0.0" }
                """);

        Lockfile existing = cc.jumpkick.lock.LockfileReader.read(rootLock);
        Lockfile updated = AutoLock.maybeReLock(
                ws.resolve("app"),
                existing,
                rootLock,
                tmp.resolve("cache"),
                repo.toUri(),
                "test",
                List.of(),
                true,
                ResolveObserver.NOOP,
                null);

        assertThat(updated).as("stale member relock produced a lock").isNotNull();
        List<String> names =
                updated.artifacts().stream().map(Lockfile.Artifact::name).toList();
        // The union: app's own dep AND the sibling-only dep — a member-scoped closure would
        // have truncated util away (the regression).
        assertThat(names).anyMatch(n -> n.contains("extra"));
        assertThat(names).anyMatch(n -> n.contains("util"));
    }

    @Test
    void relock_refreshes_module_identity_pins(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        repoArtifact(repo, "org.junit.platform", "junit-platform-launcher", "1.10.0");
        repoArtifact(repo, "org.junit.jupiter", "junit-jupiter", "5.10.0");

        Path proj = tmp.resolve("proj");
        Files.createDirectories(proj);
        Files.writeString(proj.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "solo"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);

        // The on-disk lock pins 1.0.0 identity (digest stamped against the 1.0.0 manifest).
        Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(proj);
        Lockfile stale = new Lockfile(1, "test", "jk-test", List.of())
                .withModules(List.of(new Lockfile.ModuleEntry(
                        ".", "com.example", "solo", "1.0.0", "21", 21, null, null, null, null, null, null)));
        LockfileWriter.write(stale, lockFile);

        // Then the project bumps its version: content digest diverges (mtimes are irrelevant
        // under the LockFreshness digest regime) and auto-relock must restamp identity.
        Files.writeString(proj.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "solo"
                version = "2.0.0"
                jdk = 25
                java = 25
                """);

        Lockfile updated = AutoLock.maybeReLock(
                proj,
                cc.jumpkick.lock.LockfileReader.read(lockFile),
                lockFile,
                tmp.resolve("cache"),
                repo.toUri(),
                "test",
                List.of(),
                true,
                ResolveObserver.NOOP,
                null);

        assertThat(updated).isNotNull();
        assertThat(updated.modules())
                .as("auto-relock IS a re-lock — [[module]] identity must be restamped")
                .anyMatch(m -> m.path().equals(".") && m.version().equals("2.0.0"));
        // And the write that landed on disk agrees.
        Lockfile onDisk = cc.jumpkick.lock.LockfileReader.read(lockFile);
        assertThat(onDisk.modules()).anyMatch(m -> m.version().equals("2.0.0"));
    }
}
