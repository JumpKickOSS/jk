// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.resolver.ResolveObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AutoLock#maybeReLock} inside a workspace: a stale relock triggered from one member must
 * resolve the whole workspace union into the root {@code jk-lock.toml} — never truncate it to the
 * triggering module's closure (JK-1304). Offline: deps resolve from a hand-written {@code file://}
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
        Files.writeString(
                vDir.resolve(artifact + "-" + version + ".pom"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>jar</packaging>
                </project>
                """
                        .formatted(group, artifact, version));
        Files.writeString(
                dir.resolve("maven-metadata.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <release>%s</release>
                    <versions><version>%s</version></versions>
                  </versioning>
                </metadata>
                """
                        .formatted(group, artifact, version, version));
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
                jdk = 21
                java = 21

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
                jdk = 21
                java = 21

                [dependencies]
                util = { group = "com.acme", name = "util", version = "1.0.0" }
                """);
        Files.createDirectories(ws.resolve("app"));
        Files.writeString(ws.resolve("app/jk.toml"), """
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk = 21
                java = 21

                [dependencies]
                extra = { group = "com.acme", name = "extra", version = "1.0.0" }
                """);

        // An existing (soon stale) root lock; contents only feed conservative preferences.
        Path rootLock = cc.jumpkick.lock.LockPaths.lockFile(ws);
        LockfileWriter.write(new Lockfile(1, "test", "jk-test", List.of()), rootLock);
        // app/jk.toml newer than the root lock → stale from app's perspective.
        Files.setLastModifiedTime(rootLock, FileTime.from(Instant.now().minusSeconds(120)));
        Files.setLastModifiedTime(ws.resolve("app/jk.toml"), FileTime.from(Instant.now()));

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
        // have truncated util away (the JK-1304 regression).
        assertThat(names).anyMatch(n -> n.contains("extra"));
        assertThat(names).anyMatch(n -> n.contains("util"));
    }
}
