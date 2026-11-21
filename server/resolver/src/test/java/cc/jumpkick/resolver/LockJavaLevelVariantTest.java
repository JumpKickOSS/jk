// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.GradleModuleMetadata;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lock chooses a module's Gradle runtime variant for the {@code java} its modules compile for: a
 * variant built for a newer JVM is passed over, and a workspace lock is chosen for its lowest member;
 * with no {@code java} declared, for the JDK the modules build on.
 */
class LockJavaLevelVariantTest {

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
        // Only the older pinned reaches widget, so only a member pinning it holds a variant.
        upstream.metadata(GROUP, "pinned", "1.0.0", "2.0.0")
                .pom(
                        GROUP,
                        "pinned",
                        "1.0.0",
                        pinnedPom(
                                "1.0.0",
                                "<dependencies><dependency><groupId>" + GROUP
                                        + "</groupId><artifactId>widget</artifactId><version>1.0.0</version></dependency>"
                                        + "</dependencies>"))
                .pom(GROUP, "pinned", "2.0.0", pinnedPom("2.0.0", ""));
    }

    @Test
    void a_project_on_an_older_java_locks_the_variant_built_for_it(@TempDir Path tmp) throws Exception {
        Lockfile lock = new LockOrchestrator(repos(tmp)).lock(manifest("app", 17), "test");

        assertThat(modules(lock)).contains("widget-jdk11").doesNotContain("widget-jdk21");
    }

    @Test
    void a_project_on_the_newest_java_locks_the_newest_variant(@TempDir Path tmp) throws Exception {
        Lockfile lock = new LockOrchestrator(repos(tmp)).lock(manifest("app", 25), "test");

        assertThat(modules(lock)).contains("widget-jdk21").doesNotContain("widget-jdk11");
    }

    @Test
    void a_project_that_declares_no_java_locks_for_the_jdk_it_builds_on(@TempDir Path tmp) throws Exception {
        Lockfile onOlder = new LockOrchestrator(repos(tmp)).withJdkLevel(17).lock(manifest("app", 0), "test");
        Lockfile onNewer = new LockOrchestrator(repos(tmp)).withJdkLevel(25).lock(manifest("app", 0), "test");

        assertThat(modules(onOlder)).contains("widget-jdk11").doesNotContain("widget-jdk21");
        assertThat(modules(onNewer)).contains("widget-jdk21").doesNotContain("widget-jdk11");
    }

    @Test
    void a_declared_java_outranks_the_jdk_the_project_builds_on(@TempDir Path tmp) throws Exception {
        Lockfile lock = new LockOrchestrator(repos(tmp)).withJdkLevel(25).lock(manifest("app", 17), "test");

        assertThat(modules(lock)).contains("widget-jdk11").doesNotContain("widget-jdk21");
    }

    /**
     * A later solve of a series reuses a member's answer only when its inputs are equal, and the
     * level the workspace's variants are chosen for is one: a sibling lowering its java moves it,
     * even where the merged solve reaches no module with variants.
     */
    @Test
    void a_series_re_solves_a_member_whose_sibling_lowered_the_workspace_java(@TempDir Path tmp) throws Exception {
        LockOrchestrator.Rounds series = new LockOrchestrator.Rounds();
        JkBuild current = pinning(manifest("current", 25), "1.0.0");
        JkBuild root = pinning(manifest("root", 25), "2.0.0");

        Lockfile first = new LockOrchestrator(repos(tmp))
                .withRounds(series)
                .withMembers(List.of(
                        new LockOrchestrator.Member("legacy", pinning(manifest("legacy", 25), "2.0.0")),
                        new LockOrchestrator.Member("current", current)))
                .lock(root, "test");
        int solvedFirst = series.memberSolves();
        series.resetCounts();
        Lockfile lowered = new LockOrchestrator(repos(tmp))
                .withRounds(series)
                .withMembers(List.of(
                        new LockOrchestrator.Member("legacy", pinning(manifest("legacy", 17), "2.0.0")),
                        new LockOrchestrator.Member("current", current)))
                .lock(root, "test");

        assertThat(solvedFirst).isEqualTo(1);
        assertThat(modules(first.forMember("current"))).contains("widget-jdk21");
        assertThat(series.memberReuses()).isZero();
        assertThat(modules(lowered.forMember("current")))
                .contains("widget-jdk11")
                .doesNotContain("widget-jdk21");
    }

    @Test
    void a_series_reuses_a_member_whose_workspace_java_held(@TempDir Path tmp) throws Exception {
        LockOrchestrator.Rounds series = new LockOrchestrator.Rounds();
        List<LockOrchestrator.Member> members = List.of(
                new LockOrchestrator.Member("legacy", pinning(manifest("legacy", 25), "2.0.0")),
                new LockOrchestrator.Member("current", pinning(manifest("current", 25), "1.0.0")));
        JkBuild root = pinning(manifest("root", 25), "2.0.0");

        new LockOrchestrator(repos(tmp)).withRounds(series).withMembers(members).lock(root, "test");
        series.resetCounts();
        new LockOrchestrator(repos(tmp)).withRounds(series).withMembers(members).lock(root, "test");

        assertThat(series.memberReuses()).isEqualTo(1);
        assertThat(series.memberSolves()).isZero();
    }

    @Test
    void a_workspace_locks_for_its_lowest_member_java(@TempDir Path tmp) throws Exception {
        Lockfile lock = new LockOrchestrator(repos(tmp))
                .withMembers(List.of(
                        new LockOrchestrator.Member("legacy", manifest("legacy", 17)),
                        new LockOrchestrator.Member("current", manifest("current", 25))))
                .lock(manifest("root", 25), "test");

        assertThat(modules(lock)).contains("widget-jdk11").doesNotContain("widget-jdk21");
    }

    @Test
    void a_relock_after_java_is_lowered_keeps_no_variant_the_new_java_cannot_run(@TempDir Path tmp) throws Exception {
        Lockfile atNewest = new LockOrchestrator(repos(tmp)).lock(manifest("app", 25), "test");

        Lockfile lowered = new LockOrchestrator(repos(tmp))
                .lockConservative(manifest("app", 17), atNewest, "test", List.of(), true, ResolveObserver.NOOP);

        assertThat(modules(atNewest)).contains("widget-jdk21");
        assertThat(modules(lowered)).contains("widget-jdk11").doesNotContain("widget-jdk21");
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

    private static JkBuild manifest(String name, int java) {
        EnumMap<Scope, List<Dependency>> deps = new EnumMap<>(Scope.class);
        deps.putAll(Map.of(Scope.MAIN, List.of(new Dependency(GROUP + ":widget", VersionSelector.parse("1.0.0")))));
        return new JkBuild(new Project("com.example", name, "0.1.0", java), new JkBuild.Dependencies(deps));
    }

    /**
     * {@code build} depending on an exact {@code pinned} alone: a member whose pin is not the
     * workspace's is solved on its own.
     */
    private static JkBuild pinning(JkBuild build, String pinned) {
        EnumMap<Scope, List<Dependency>> deps = new EnumMap<>(Scope.class);
        deps.put(Scope.MAIN, List.of(new Dependency(GROUP + ":pinned", VersionSelector.parse("=" + pinned))));
        return new JkBuild(build.project(), new JkBuild.Dependencies(deps));
    }

    private static String pinnedPom(String version, String dependencies) {
        return "<project><groupId>" + GROUP + "</groupId><artifactId>pinned</artifactId><version>" + version
                + "</version>" + dependencies + "</project>";
    }

    private RepoGroup repos(Path tmp) {
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tmp.resolve("cache"))));
    }
}
