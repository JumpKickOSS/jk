// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.net.URI;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class LogbackCoreEdgeTest {
    @Test
    void lock_records_logback_core_as_dep_of_classic(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("c"));
        MavenRepo central =
                new MavenRepo("central", URI.create("https://repo.maven.apache.org/maven2/"), new Http(), cas);
        EnumMap<Scope, List<Dependency>> by = new EnumMap<>(Scope.class);
        by.put(
                Scope.MAIN,
                List.of(new Dependency("ch.qos.logback:logback-classic", VersionSelector.parseFloating("1.5.37"))));
        // Platform BOM like Micronaut — enforces managed pins
        by.put(
                Scope.PLATFORM,
                List.of(Dependency.of(
                        "platform",
                        "io.micronaut.platform:micronaut-platform",
                        VersionSelector.parseFloating("=5.1.0"))));
        JkBuild project =
                new JkBuild(new JkBuild.Project("com.example", "demo", "0.1.0", 25), new JkBuild.Dependencies(by));
        Lockfile lock = new LockOrchestrator(RepoGroup.of(central)).lock(project, "test");
        Lockfile.Artifact classic = lock.artifacts().stream()
                .filter(a -> a.packageKey().startsWith("ch.qos.logback:logback-classic"))
                .findFirst()
                .orElseThrow();
        System.err.println("CLASSIC DEPS=" + classic.deps());
        assertThat(classic.deps().stream().anyMatch(d -> d.contains("logback-core")))
                .as("logback-classic must list logback-core: %s", classic.deps())
                .isTrue();
        assertThat(lock.artifacts().stream().anyMatch(a -> a.name().contains("logback-core")))
                .isTrue();
    }
}
