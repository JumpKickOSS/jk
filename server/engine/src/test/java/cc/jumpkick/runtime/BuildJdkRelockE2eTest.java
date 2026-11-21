// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.repo.GradleModuleMetadata;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.KmpRedirects;
import cc.jumpkick.resolver.LockOrchestrator;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.SysProps;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project that declares no {@code java} locks each Gradle module's runtime variant for the JDK
 * it builds on. A lock made on JDK 17 holds the variant built for 11; a build on a JDK of 21 or
 * newer finds it stale for its JDK, relocks before it compiles, and builds on the variant for 21.
 */
@Tag("integration")
@SysProps.TempRoots({"jk.env.JK_STORE_DIR", "jk.m2.local"})
class BuildJdkRelockE2eTest {

    private static final String GROUP = "com.example.perjvm";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void publish() {
        KmpRedirects.clearProcessCache();
        GradleModuleMetadata.clearParseCache();
        RepoGroup.clearProcessFetchCache();
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        upstream.metadata(GROUP, "widget", "1.0.0");
        upstream.pom(
                GROUP,
                "widget",
                "1.0.0",
                "<?xml version=\"1.0\"?>\n<!-- " + GradleModuleMetadata.POM_MARKER + " -->\n"
                        + MavenStub.emptyPom(GROUP, "widget", "1.0.0"));
        upstream.text(MavenStub.path(GROUP, "widget", "1.0.0", ".module"), """
                { "formatVersion": "1.1", "variants": [ %s, %s ] }
                """.formatted(variant(21), variant(11)));
        upstream.leaf(GROUP, "widget-jdk21", "1.0.0").leaf(GROUP, "widget-jdk11", "1.0.0");
        AutoLock.installBuildJdk();
    }

    @AfterEach
    void forgetTheBuildJdk() {
        LockFreshness.installBuildJdk(owner -> 0);
    }

    @Test
    void a_build_on_a_jdk_the_lock_s_variants_do_not_hold_for_relocks_first(@TempDir Path tmp) throws Exception {
        Path dir = project(tmp);
        int jdk = AutoLock.buildJdkLevel(dir);
        assumeThat(jdk).as("the build JDK chooses the variant for 21").isGreaterThanOrEqualTo(21);
        Path lockFile = LockPaths.lockFile(dir);
        Path cache = tmp.resolve("cache");
        // The lock as a build on JDK 17 wrote it: the variant for 11, chosen for 11 to 20.
        Lockfile on17 = new LockOrchestrator(RepoGroup.of(
                        new MavenRepo("central", http.base(), new Http(), new Cas(cache.resolve("orchestrator")))))
                .withJdkLevel(17)
                .lock(JkBuildParser.parse(dir.resolve("jk.toml")), "test");
        LockfileWriter.write(on17, lockFile);
        assertThat(modules(LockfileReader.read(lockFile))).contains("widget-jdk11");
        String why = LockFreshness.staleReason(dir, lockFile);
        assertThat(why).as("stale for the JDK alone").isNotNull();
        assertThat(LockFreshness.jdkMoved(Objects.requireNonNull(why))).as(why).isTrue();

        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(dir, cache, null, 0, null, true, false, 1, null, false, true),
                new WorkspaceBuildListener() {});

        assertThat(result.success()).as(String.join("\n", result.errors())).isTrue();
        Lockfile relocked = LockfileReader.read(lockFile);
        assertThat(relocked.variants()).isEqualTo(new Lockfile.VariantJdk(jdk, 21, Integer.MAX_VALUE));
        assertThat(modules(relocked)).contains("widget-jdk21").doesNotContain("widget-jdk11");
        assertThat(LockFreshness.staleReason(dir, lockFile)).isNull();
    }

    /** A project that declares no {@code java} and depends on the module with a variant per JVM. */
    private Path project(Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"

                [dependencies]
                widget = { group = "%s", version = "1.0.0" }

                [repositories]
                central = "%s"
                """.formatted(GROUP, http.base()));
        Path src = Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(src.resolve("Main.java"), "package com.example;\n\npublic final class Main {}\n");
        return dir;
    }

    private static String variant(int level) {
        return """
                { "name": "jdk%1$dRuntimeElements",
                  "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime",
                    "org.gradle.jvm.version": %1$d },
                  "available-at": { "group": "%2$s", "module": "widget-jdk%1$d", "version": "1.0.0" } }
                """.formatted(level, GROUP);
    }

    private static List<String> modules(Lockfile lock) {
        return lock.artifacts().stream()
                .map(Lockfile.Artifact::packageKey)
                .filter(key -> key.startsWith(GROUP + ":"))
                .map(key -> key.split(":")[1])
                .toList();
    }
}
