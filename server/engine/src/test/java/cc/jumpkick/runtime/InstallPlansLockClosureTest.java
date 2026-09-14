// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.publish.PublishablePom;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.repo.RepoGroup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The installed POM manages the lock's whole runtime closure, so the classpath a worker launch
 * rebuilds from it is the closure the build tested — not what each transitive POM asks for.
 */
class InstallPlansLockClosureTest {

    private static final String CENTRAL = "central+https://repo.maven.apache.org/maven2/";

    private static JkBuild declaringLib() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(new Dependency("com.acme:lib", VersionSelector.parse("=1.0"))));
        byScope.put(Scope.TEST, List.of(new Dependency("org.junit:junit", VersionSelector.parse("=5.0"))));
        return JkBuild.builder(Project.builder("cc.jumpkick", "jk-foo", "1.0")
                        .jdkMajor(25)
                        .java(25)
                        .m2install(false)
                        .build())
                .dependencies(new JkBuild.Dependencies(byScope))
                .build();
    }

    /** lib 1.0 asks for leaf 1.0; the lock resolved leaf to 3.0 (another root raised it). */
    private static Lockfile lock() {
        return new Lockfile(
                1,
                "jk test",
                "pubgrub-v1",
                List.of(
                        new Lockfile.Artifact(
                                "com.acme:lib:jar:",
                                "1.0",
                                CENTRAL,
                                null,
                                null,
                                List.of(Scope.MAIN),
                                List.of("com.acme:leaf:jar:@3.0 <- 1.0")),
                        new Lockfile.Artifact(
                                "com.acme:leaf:jar:", "3.0", CENTRAL, null, null, List.of(Scope.MAIN), List.of()),
                        new Lockfile.Artifact(
                                "org.junit:junit:jar:", "5.0", CENTRAL, null, null, List.of(Scope.TEST), List.of()),
                        new Lockfile.Artifact(
                                "com.acme:unrelated:jar:",
                                "9.0",
                                CENTRAL,
                                null,
                                null,
                                List.of(Scope.MAIN),
                                List.of())));
    }

    @Test
    void the_closure_follows_lock_edges_from_the_runtime_roots_only() {
        List<Coordinate> closure = InstallPlans.lockClosure(declaringLib(), lock());

        assertThat(closure)
                .containsExactly(Coordinate.of("com.acme", "lib", "1.0"), Coordinate.of("com.acme", "leaf", "3.0"));
        assertThat(InstallPlans.lockClosure(declaringLib(), null)).isEmpty();
    }

    @Test
    void a_launch_rebuilt_from_the_installed_pom_runs_on_the_locked_transitive(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        JkBuild project = declaringLib();
        Lockfile lock = lock();
        String pom = PublishablePom.render(
                        project, null, Set.of(), InstallPlans.lockPins(lock), InstallPlans.lockClosure(project, lock))
                .xml();

        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-foo", "1.0");
        Path workerJar = put(store, RepoArtifactResolver.JK_LOCAL, MavenLayout.artifactPath(worker), "worker");
        put(store, RepoArtifactResolver.JK_LOCAL, MavenLayout.pomPath(worker), pom);
        Coordinate lib = Coordinate.of("com.acme", "lib", "1.0");
        put(store, "central", MavenLayout.artifactPath(lib), "lib");
        put(store, "central", MavenLayout.pomPath(lib), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>lib</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>com.acme</groupId><artifactId>leaf</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """);
        for (String v : List.of("1.0", "3.0")) {
            Coordinate leaf = Coordinate.of("com.acme", "leaf", v);
            put(store, "central", MavenLayout.artifactPath(leaf), "leaf-" + v);
            put(store, "central", MavenLayout.pomPath(leaf), """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.acme</groupId><artifactId>leaf</artifactId><version>%s</version>
                    </project>
                    """.formatted(v));
        }

        List<String> names = PomRuntimeClasspath.resolve(workerJar, fileRepos(store)).stream()
                .map(p -> p.getFileName().toString())
                .toList();

        assertThat(names).containsExactly("jk-foo-1.0.jar", "lib-1.0.jar", "leaf-3.0.jar");
    }

    private static RepoGroup fileRepos(Path store) {
        Http http = new Http();
        Cas cas = new Cas(store);
        return new RepoGroup(List.of(
                new MavenRepo(
                        RepoArtifactResolver.JK_LOCAL,
                        store.resolve("repos")
                                .resolve(RepoArtifactResolver.JK_LOCAL)
                                .toUri(),
                        http,
                        cas),
                new MavenRepo(
                        "central", store.resolve("repos").resolve("central").toUri(), http, cas)));
    }

    private static Path put(Path store, String repo, String rel, String content) throws Exception {
        Path f = store.resolve("repos").resolve(repo).resolve(rel);
        Files.createDirectories(f.getParent());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(f, bytes);
        RepoArtifactStore.forStoreId(store, repo).writeMemo(rel, f, Hashing.sha256Hex(bytes));
        return f;
    }
}
