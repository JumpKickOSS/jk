// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PathSource;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.testing.LoopbackHttp;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The features a consumer activated on a path library are recorded on that library's lock row. The
 * map arrives on the locking thread and the row assembler reads it on the io pool, so the value has
 * to reach a thread that never ran {@code lock()}.
 */
class LockOrchestratorCrossPackageFeaturesTest {

    private static final byte[] EMPTY_JAR = {
        0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @Test
    void activated_features_reach_the_library_row_assembled_on_the_io_pool(@TempDir Path dir) throws Exception {
        // The row assembler fetches the library's artifact; the stub repo serves it under the
        // synthetic path: coordinate the solver stub below reports.
        http.served().put("/path/widget/1.0/widget-1.0.jar", EMPTY_JAR);
        Dependency consumer = Dependency.pathByName("widget", new PathSource("widget"));
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(consumer));
        JkBuild project = new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));
        // Solver stub: the library resolves, everything else (the activated extra, junit) is absent.
        Resolver onlyTheLibrary = roots -> roots.stream()
                        .anyMatch(d -> d.module().equals("path:widget"))
                ? new Resolution(Map.of("path:widget", new Resolution.ResolvedModule("path:widget", "1.0", List.of())))
                : new Resolution(Map.of());
        RepoGroup repos = RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(dir.resolve("cache"))));

        Lockfile lock = new LockOrchestrator(repos, onlyTheLibrary)
                .withActivatedFeatures(Map.of("path:widget", List.of("mysql")))
                .lock(project, "test");

        Lockfile.Artifact widget = lock.artifacts().stream()
                .filter(a -> a.name().equals("path:widget"))
                .findFirst()
                .orElseThrow();
        assertThat(widget.pinnedBy()).isEqualTo("features:mysql");
        // Rows are assembled on JkThreads.io(), a thread-per-task pool: it never lends the caller's
        // own thread, so the assertion above was made across threads, not on this one.
        Thread assembler = CompletableFuture.supplyAsync(Thread::currentThread, JkThreads.io())
                .get();
        assertThat(assembler).isNotSameAs(Thread.currentThread());
    }
}
