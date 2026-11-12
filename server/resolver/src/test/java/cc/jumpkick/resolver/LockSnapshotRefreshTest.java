// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.repo.RepoProcessMemos;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code -SNAPSHOT} version is a name for whatever its source holds now: a {@code mvn install}
 * or {@code jk snapshot} into a Maven local repository, or a repository that republishes it in
 * place. Every lock reads it again from there rather than from the copy the store took the first
 * time; a timestamped snapshot names one build and is kept like a release.
 */
class LockSnapshotRefreshTest {

    private static final String SNAPSHOT = "1.0-SNAPSHOT";
    private static final String TIMESTAMPED = "1.0-20260101.120000-1";

    @BeforeAll
    static void isolateM2(@TempDir Path m2) {
        System.setProperty("jk.m2.local", m2.toString());
    }

    @RegisterExtension
    final LoopbackHttp central = new LoopbackHttp().concurrent();

    @RegisterExtension
    final LoopbackHttp snapshots = new LoopbackHttp().concurrent();

    @BeforeEach
    void publish() {
        RepoProcessMemos.clear();
        new MavenStub(central)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0")
                .leaf("com.foo", "extra", "1.0");
    }

    @Test
    void a_snapshot_installed_again_into_a_local_repository_is_what_the_next_lock_pins(@TempDir Path dir)
            throws Exception {
        Path local = dir.resolve("m2");
        install(local, SNAPSHOT, jar("A"), MavenStub.emptyPom("com.foo", "lib", SNAPSHOT));
        Lockfile first = lock(dir, localRepo(local), SNAPSHOT);
        assertThat(sha256Of(first, "com.foo:lib:")).isEqualTo("sha256:" + Hashing.sha256Hex(jar("A")));

        install(local, SNAPSHOT, jar("B"), pomDependingOnExtra(SNAPSHOT));
        Lockfile second = lock(dir, localRepo(local), SNAPSHOT);

        assertThat(sha256Of(second, "com.foo:lib:"))
                .as("the re-installed jar, not the store's first copy")
                .isEqualTo("sha256:" + Hashing.sha256Hex(jar("B")));
        assertThat(second.artifacts()).as("the re-installed POM's dependency").anyMatch(a -> a.name()
                .startsWith("com.foo:extra:"));
    }

    @Test
    void a_snapshot_republished_in_place_by_a_remote_is_what_the_next_lock_pins(@TempDir Path dir) throws Exception {
        serve(SNAPSHOT, jar("A"), MavenStub.emptyPom("com.foo", "lib", SNAPSHOT));
        Lockfile first = lock(dir, remoteRepo(dir), SNAPSHOT);
        assertThat(sha256Of(first, "com.foo:lib:")).isEqualTo("sha256:" + Hashing.sha256Hex(jar("A")));

        serve(SNAPSHOT, jar("B"), pomDependingOnExtra(SNAPSHOT));
        Lockfile second = lock(dir, remoteRepo(dir), SNAPSHOT);

        assertThat(sha256Of(second, "com.foo:lib:")).isEqualTo("sha256:" + Hashing.sha256Hex(jar("B")));
        assertThat(second.artifacts()).anyMatch(a -> a.name().startsWith("com.foo:extra:"));
    }

    @Test
    void a_timestamped_snapshot_is_kept_like_a_release(@TempDir Path dir) throws Exception {
        serve(TIMESTAMPED, jar("A"), MavenStub.emptyPom("com.foo", "lib", TIMESTAMPED));
        lock(dir, remoteRepo(dir), TIMESTAMPED);
        snapshots.clearRequests();

        Lockfile second = lock(dir, remoteRepo(dir), TIMESTAMPED);

        assertThat(sha256Of(second, "com.foo:lib:")).isEqualTo("sha256:" + Hashing.sha256Hex(jar("A")));
        assertThat(snapshots.requested())
                .as("the stored POM answers; nothing about it is asked again")
                .noneMatch(path -> path.contains("/lib-" + TIMESTAMPED + ".pom"));
    }

    private Lockfile lock(Path dir, MavenRepo snapshotRepo, String version) throws Exception {
        MavenRepo releases = new MavenRepo("central", central.base(), new Http(), new Cas(dir.resolve("cache")))
                .withPolicy(true, false);
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(new Dependency("com.foo:lib", VersionSelector.parse("=" + version))));
        JkBuild project = new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));
        return new LockOrchestrator(new RepoGroup(List.of(releases, snapshotRepo))).lock(project, "test");
    }

    private static MavenRepo localRepo(Path local) {
        return new MavenRepo("local", local.toUri(), new Http(), new Cas(local.resolveSibling("cache")))
                .withPolicy(true, true);
    }

    private MavenRepo remoteRepo(Path dir) {
        return new MavenRepo("snapshots", snapshots.base(), new Http(), new Cas(dir.resolve("cache")))
                .withPolicy(false, true);
    }

    private void serve(String version, byte[] jar, String pom) {
        new MavenStub(snapshots)
                .pomOnly("com.foo", "lib", version, pom)
                .bytes(MavenStub.path("com.foo", "lib", version, ".jar"), jar);
    }

    private static void install(Path local, String version, byte[] jar, String pom) throws IOException {
        Path jarPath =
                local.resolve(MavenStub.path("com.foo", "lib", version, ".jar").substring(1));
        Files.createDirectories(jarPath.getParent());
        Files.write(jarPath, jar);
        Files.writeString(
                local.resolve(MavenStub.path("com.foo", "lib", version, ".pom").substring(1)), pom);
        // The release the second POM depends on is in the local repository as well.
        Path extra =
                local.resolve(MavenStub.path("com.foo", "extra", "1.0", ".pom").substring(1));
        Files.createDirectories(extra.getParent());
        Files.writeString(extra, MavenStub.emptyPom("com.foo", "extra", "1.0"));
        Files.write(extra.resolveSibling("extra-1.0.jar"), MavenStub.EMPTY_JAR);
    }

    private static String pomDependingOnExtra(String version) {
        return "<project><groupId>com.foo</groupId><artifactId>lib</artifactId><version>" + version
                + "</version><dependencies><dependency><groupId>com.foo</groupId><artifactId>extra</artifactId>"
                + "<version>1.0</version></dependency></dependencies></project>";
    }

    private static @Nullable String sha256Of(Lockfile lock, String prefix) {
        return lock.artifacts().stream()
                .filter(a -> a.name().startsWith(prefix))
                .findFirst()
                .orElseThrow()
                .checksum();
    }

    /** A jar whose one entry holds {@code marker}: two markers, two digests. */
    private static byte[] jar(String marker) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry entry = new ZipEntry("marker.txt");
            entry.setTime(0);
            zip.putNextEntry(entry);
            zip.write(marker.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
