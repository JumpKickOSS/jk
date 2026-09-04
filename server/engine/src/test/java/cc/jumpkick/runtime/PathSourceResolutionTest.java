// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PathSource;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.LockOrchestrator;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end (offline) wiring of a local-path dependency through the resolver: a consuming project
 * declares a {@code path} dep on a sibling jk library; {@link PathSourceResolution#prepare}
 * materializes it, rewrites it to an exact coordinate pin, and augments the repo group; the real
 * {@link LockOrchestrator} then solves it from the local {@code file://} repo.
 */
@Tag("integration")
class PathSourceResolutionTest {

    private static void writeLibrary(Path libDir) throws Exception {
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("jk.toml"), """
                group   = "com.acme"
                name    = "widgets"
                version = "0.1.0"
                jdk     = 25
                java    = 25
                """);
        Path src = libDir.resolve("src/main/java/acme/Widget.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package acme; public class Widget {}");
    }

    /** A consuming project with a single path dependency on {@code ./lib}. */
    private static JkBuild consumer() {
        Project project = new Project("com.example", "app", "0.1.0", 25);
        Dependency path = Dependency.pathByName("widgets", new PathSource("./lib"));
        JkBuild.Dependencies deps = new JkBuild.Dependencies(Map.of(Scope.MAIN, List.of(path)));
        return new JkBuild(project, deps);
    }

    @Test
    void materializes_and_pins_a_path_dependency(@TempDir Path tmp) throws Exception {
        writeLibrary(tmp.resolve("lib"));
        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup baseRepos =
                RepoGroup.of(new MavenRepo("central", RepositorySpec.MAVEN_CENTRAL.url(), new Http(), cas));

        PathSourceResolution.Prepared prep = PathSourceResolution.prepare(
                consumer(), baseRepos, cas, tmp, Path.of(System.getProperty("java.home")), "test");

        // The path dep is gone; a pinned com.acme:widgets:0.1.0 coordinate replaces it.
        List<Dependency> mainDeps = prep.project().dependencies().of(Scope.MAIN);
        assertThat(mainDeps).hasSize(1);
        Dependency pinned = mainDeps.get(0);
        assertThat(pinned.isPath()).isFalse();
        assertThat(pinned.module()).isEqualTo("com.acme:widgets");
        assertThat(pinned.version().raw()).isEqualTo("=0.1.0");
        // A file:// repo for the built artifact was prepended to the group.
        assertThat(prep.repos().repos()).hasSizeGreaterThan(baseRepos.repos().size());
        assertThat(prep.repos().repos().get(0).baseUrl().getScheme()).isEqualTo("file");

        // The real solver resolves the pin from the local file:// repo (offline).
        Lockfile lock = new LockOrchestrator(prep.repos()).lock(prep.project(), "test", List.of(), true);
        Lockfile.Artifact widgets = lock.artifacts().stream()
                .filter(p -> p.matchesModule("com.acme:widgets"))
                .findFirst()
                .orElseThrow();
        assertThat(widgets.version()).isEqualTo("0.1.0");
        // Path deps carry no git provenance.
        assertThat(widgets.git()).isNull();
    }

    @Test
    void a_project_without_path_deps_is_a_no_op(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup baseRepos =
                RepoGroup.of(new MavenRepo("central", RepositorySpec.MAVEN_CENTRAL.url(), new Http(), cas));
        JkBuild plain = new JkBuild(new Project("com.example", "app", "0.1.0", 25), new JkBuild.Dependencies(Map.of()));

        PathSourceResolution.Prepared prep = PathSourceResolution.prepare(
                plain, baseRepos, cas, tmp, Path.of(System.getProperty("java.home")), "test");

        assertThat(prep.project()).isSameAs(plain);
        assertThat(prep.repos()).isSameAs(baseRepos);
    }

    /**
     * A feature selection on a path library survives the rewrite: the library's optional dependency
     * roots in the consumer's main graph, and the library's lock row names the activated feature.
     */
    @Test
    void a_feature_selection_on_a_path_dependency_pulls_the_library_optional_dep_and_marks_the_row(@TempDir Path tmp)
            throws Exception {
        Path lib = tmp.resolve("lib");
        writeLibrary(lib);
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.acme"
                name    = "widgets"
                version = "0.1.0"
                jdk     = 25
                java    = 25

                [dependencies]
                extra = { group = "com.acme", name = "extra", version = "1.0", optional = true }

                [features]
                default = []
                db = { deps = ["extra"] }
                """);
        LoopbackHttp http = new LoopbackHttp();
        http.start();
        try {
            new MavenStub(http)
                    .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                    .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0")
                    .leaf("com.acme", "extra", "1.0");
            Cas cas = new Cas(tmp.resolve("cas"));
            RepoGroup baseRepos = RepoGroup.of(new MavenRepo("maven-stub", http.base(), new Http(), cas));
            Dependency path =
                    Dependency.pathByName("widgets", new PathSource("./lib")).withFeatures(List.of("db"), false);
            JkBuild consumer = new JkBuild(
                    new Project("com.example", "app", "0.1.0", 25),
                    new JkBuild.Dependencies(Map.of(Scope.MAIN, List.of(path))));

            PathSourceResolution.Prepared prep = PathSourceResolution.prepare(
                    consumer, baseRepos, cas, tmp, Path.of(System.getProperty("java.home")), "test");

            assertThat(prep.project().dependencies().of(Scope.MAIN).stream().map(Dependency::module))
                    .containsExactly("com.acme:widgets", "com.acme:extra");
            assertThat(prep.project().dependencies().of(Scope.MAIN).get(1).optional())
                    .isFalse();
            assertThat(prep.activatedFeatures()).containsExactly(Map.entry("com.acme:widgets", List.of("db")));

            Lockfile lock = new LockOrchestrator(prep.repos())
                    .withActivatedFeatures(prep.activatedFeatures())
                    .lock(prep.project(), "test", List.of(), true);
            Lockfile.Artifact widgets = lock.artifacts().stream()
                    .filter(p -> p.matchesModule("com.acme:widgets"))
                    .findFirst()
                    .orElseThrow();
            assertThat(widgets.pinnedBy()).isEqualTo("features:db");
            assertThat(lock.artifacts().stream().anyMatch(p -> p.matchesModule("com.acme:extra")))
                    .isTrue();
        } finally {
            http.stop();
        }
    }
}
