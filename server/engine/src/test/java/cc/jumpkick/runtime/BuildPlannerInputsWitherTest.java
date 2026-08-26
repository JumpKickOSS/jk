// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** JK-2102: the request-knob decoration shared by every assemblePlan branch. */
class BuildPlannerInputsWitherTest {

    @Test
    void request_knob_withers_set_only_their_field() {
        Path dir = Path.of("/w/app");
        BuildPlanner.Inputs base = new BuildPlanner.Inputs(
                dir,
                Path.of("/cache"),
                dir.resolve("jk.toml"),
                dir.resolve("jk-lock.toml"),
                dir,
                1,
                0,
                null,
                null,
                true,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        BuildPlanner.Inputs decorated = base.withWorkerCount(6)
                .withProfileName("ci")
                .withProjectModules(Set.of(dir))
                .withVariant("blue", Map.of("K", "v"))
                .withEphemeralActions(true);
        assertThat(decorated.workerCount()).isEqualTo(6);
        assertThat(decorated.profileName()).isEqualTo("ci");
        assertThat(decorated.projectModules()).containsExactly(dir);
        assertThat(decorated.variant()).isEqualTo("blue");
        assertThat(decorated.clientEnv()).containsEntry("K", "v");
        assertThat(decorated.ephemeralActions()).isTrue();
        // untouched fields survive the chain
        assertThat(decorated.dir()).isEqualTo(base.dir());
        assertThat(decorated.skipTests()).isEqualTo(base.skipTests());
        assertThat(decorated.compileOnly()).isEqualTo(base.compileOnly());
    }
}
