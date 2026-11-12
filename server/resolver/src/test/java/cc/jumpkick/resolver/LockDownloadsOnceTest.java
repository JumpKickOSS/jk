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
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** A first lock downloads each artifact once, however many of its graphs reach it. */
class LockDownloadsOnceTest {

    @RegisterExtension
    final LoopbackHttp central = new LoopbackHttp().concurrent();

    @BeforeEach
    void publish() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        new MavenStub(central)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0")
                .leaf("com.foo", "shared", "1.0");
    }

    @Test
    void an_artifact_main_and_test_both_reach_is_downloaded_once(@TempDir Path dir) throws Exception {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        Dependency shared = new Dependency("com.foo:shared", VersionSelector.parse("=1.0"));
        byScope.put(Scope.MAIN, List.of(shared));
        byScope.put(Scope.TEST, List.of(shared));
        JkBuild project = new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));

        Lockfile lock = new LockOrchestrator(RepoGroup.of(
                        new MavenRepo("central", central.base(), new Http(), new Cas(dir.resolve("cache")))))
                .lock(project, "test", List.of(), true, ResolveObserver.NOOP);

        assertThat(lock.artifacts()).anyMatch(a -> a.name().startsWith("com.foo:shared:"));
        List<String> requested = List.copyOf(central.requested());
        assertThat(asked(requested, 2))
                .as("each file is asked for once; asked in this order: %s", requested)
                .isEmpty();
        assertThat(requested).contains(MavenStub.path("com.foo", "shared", "1.0", ".jar"));
    }

    /** The paths {@code requested} holds at least {@code times} times, each once, in first-asked order. */
    private static List<String> asked(List<String> requested, int times) {
        return requested.stream()
                .filter(path -> Collections.frequency(requested, path) >= times)
                .distinct()
                .toList();
    }
}
