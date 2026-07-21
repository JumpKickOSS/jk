// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R6b: floating platform BOM selectors must fail loudly. */
class LockOrchestratorFloatingBomTest {

    @Test
    void latest_platform_bom_is_rejected(@TempDir Path tempDir) {
        JkBuild project = jkBuild(Map.of(
                Scope.PLATFORM,
                List.of(Dependency.of("bom", "org.example:bom", VersionSelector.parse("latest")))));
        LockOrchestrator orchestrator =
                new LockOrchestrator(RepoGroup.of(new MavenRepo("local", java.net.URI.create("http://127.0.0.1:1"), new Http(), new Cas(tempDir.resolve("c")))));
        assertThatThrownBy(() -> orchestrator.lock(project, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform dependency")
                .hasMessageContaining("latest")
                .hasMessageContaining("exact or caret/tilde");
    }

    private static JkBuild jkBuild(Map<Scope, List<Dependency>> byScope) {
        EnumMap<Scope, List<Dependency>> copy = new EnumMap<>(Scope.class);
        copy.putAll(byScope);
        return new JkBuild(new JkBuild.Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(copy));
    }
}
