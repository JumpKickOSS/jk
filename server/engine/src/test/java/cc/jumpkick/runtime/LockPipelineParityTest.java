// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.androidsdk.AndroidSdk;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Every entry point writes the same {@code jk-lock.toml}. Each of the four ran its own copy of the
 * pipeline and only {@code jk lock} pinned plugins and SDK components, so relocking through {@code
 * jk update}, a pre-build freshen or the stale-manifest auto-lock silently deleted the
 * {@code [[plugin]]} and {@code [[sdk]]} rows the previous lock had written.
 *
 * <p>Deps resolve from a hand-written {@code file://} Maven repo and the SDK root is a temp dir, so
 * this never touches the network.
 */
@Tag("integration")
class LockPipelineParityTest {

    /** Minimal empty-zip bytes — a valid jar as far as fetching/hashing is concerned. */
    private static final byte[] EMPTY_ZIP = {
        0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    private static final String PLUGIN_MANIFEST = """
        [plugin]
        id      = "parity"
        table   = "parity"
        version = "1.0.0"

        [schema]
        enabled = { type = "bool", default = true }

        [[contribute.step-dependency]]
        artifact      = "tools"
        sdk-component = "platform-tools"
        """;

    /** The four lock-write entry points this pipeline has. */
    private enum Entry {
        EXPLICIT_LOCK_PLAN,
        UPDATE_PLAN,
        LOCK_FLOW_FRESHEN,
        AUTO_LOCK
    }

    @AfterEach
    void releaseSdkRoot() {
        System.clearProperty(AndroidSdk.ROOT_PROPERTY);
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void plugin_and_sdk_rows_survive_every_lock_entry_point(Entry entry, @TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);

        // Baseline: `jk lock` wrote both kinds of row.
        assertRowsPresent(LockfileReader.read(f.lockFile), "the baseline jk lock");

        // A manifest edit is what puts the freshen paths in play at all.
        Files.writeString(f.manifest, Files.readString(f.manifest) + "\n# touched\n");
        drive(entry, f);

        assertRowsPresent(LockfileReader.read(f.lockFile), entry.name());
    }

    private static void assertRowsPresent(Lockfile lock, String who) {
        assertThat(lock.plugins())
                .as("[[plugin]] rows after %s", who)
                .extracting(Lockfile.PluginEntry::coordinate)
                .contains("path:parity");
        assertThat(lock.sdk())
                .as("[[sdk]] rows after %s", who)
                .extracting(Lockfile.SdkEntry::component)
                .contains("platform-tools");
    }

    private static void drive(Entry entry, Fixture f) throws Exception {
        switch (entry) {
            case EXPLICIT_LOCK_PLAN ->
                runPlan(LockPlans.lockBuildPlan(
                        f.project,
                        JkBuildParser.parse(f.manifest),
                        f.cache,
                        f.repo,
                        List.of(),
                        true,
                        false,
                        ResolveObserver.NOOP,
                        null));
            case UPDATE_PLAN ->
                runPlan(LockPlans.updateBuildPlan(
                        f.project,
                        JkBuildParser.parse(f.manifest),
                        f.cache,
                        f.repo,
                        List.of(),
                        true,
                        null,
                        ResolveObserver.NOOP));
            case LOCK_FLOW_FRESHEN -> {
                LockFlow.Result r = LockFlow.run(f.project, f.cache, List.of(), false, f.repo, true);
                assertThat(r.status()).as("LockFlow freshen: %s", r.error()).isZero();
            }
            case AUTO_LOCK -> {
                Lockfile updated = AutoLock.maybeReLock(
                        f.project,
                        LockfileReader.read(f.lockFile),
                        f.lockFile,
                        f.cache,
                        f.repo,
                        List.of(),
                        true,
                        ResolveObserver.NOOP,
                        null);
                assertThat(updated).as("auto-lock produced a lock").isNotNull();
            }
        }
    }

    private static void runPlan(BuildPlan plan) {
        BuildPlanResult result = plan.run();
        assertThat(result.success()).as("plan errors: %s", result.errors()).isTrue();
    }

    // ---- fixture ------------------------------------------------------------

    private record Fixture(Path project, Path manifest, Path lockFile, Path cache, URI repo) {}

    private static Fixture fixture(Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        repoArtifact(repo, "com.acme", "util", "1.0.0");
        // The engine adds the test-runner infra to every module's closure — stub it.
        repoArtifact(repo, "org.junit.platform", "junit-platform-launcher", "1.10.0");
        repoArtifact(repo, "org.junit.jupiter", "junit-jupiter", "5.10.0");

        // An SDK root holding one installed component, so the sdk pin has a revision to record.
        Path sdkRoot = Files.createDirectories(tmp.resolve("android-sdk").resolve("platform-tools"));
        Files.writeString(sdkRoot.resolve("source.properties"), "Pkg.Revision=35.0.1\n");
        System.setProperty(AndroidSdk.ROOT_PROPERTY, tmp.resolve("android-sdk").toString());

        Path project = Files.createDirectories(tmp.resolve("proj"));
        Path jar = writePluginJar(tmp.resolve("vendor").resolve("parity-1.0.0.jar"));
        String hex = Hashing.sha256Hex(jar);
        Path manifest = project.resolve("jk.toml");
        Files.writeString(
                manifest, """
                    group = "com.example"
                    name  = "demo"
                    version = "1.0.0"
                    jdk = 25
                    java = 25

                    [dependencies]
                    util = { group = "com.acme", name = "util", version = "1.0.0" }

                    [plugins]
                    parity = { path = "%s", sha256 = "%s" }

                    [parity]
                    enabled = true
                    """.formatted(project.relativize(jar).toString().replace('\\', '/'), hex));

        // The [parity] table only becomes a PluginConfig once the plugin is pinned in the lock and
        // its descriptor materialized — and without a PluginConfig the plugin contributes no
        // step-dependency, so nothing would ask for an sdk pin. That is what the first lock does;
        // the second one, reading the now-live table, is the baseline this test compares against.
        Path cache = tmp.resolve("cache");
        runPlan(LockPlans.lockBuildPlan(
                project,
                JkBuildParser.parse(manifest),
                cache,
                repo.toUri(),
                List.of(),
                true,
                false,
                ResolveObserver.NOOP,
                null));
        JkBuild build = JkBuildParser.reparse(manifest);
        assertThat(build.pluginConfig("parity")).as("plugin table is live").isPresent();
        runPlan(LockPlans.lockBuildPlan(
                project, build, cache, repo.toUri(), List.of(), true, false, ResolveObserver.NOOP, null));
        return new Fixture(project, manifest, LockPaths.lockFile(project), cache, repo.toUri());
    }

    private static Path writePluginJar(Path jar) throws Exception {
        Files.createDirectories(jar.getParent());
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out, mf)) {
            jos.putNextEntry(new JarEntry("jk-plugin.toml"));
            jos.write(PLUGIN_MANIFEST.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jar;
    }

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
}
