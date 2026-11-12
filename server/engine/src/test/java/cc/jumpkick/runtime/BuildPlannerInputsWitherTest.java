// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.base.SiblingArtifacts;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**: the request-knob decoration shared by every assemblePlan branch. */
class BuildPlannerInputsWitherTest {

    @Test
    void request_knob_withers_set_only_their_field() {
        Path dir = Path.of("/w/app");
        BuildPlanner.Inputs base = TestInputs.of(dir, Path.of("/cache")).withSkipTests(true);
        SiblingArtifacts.Gate gate = cancelled -> {};
        BuildPlanner.Inputs decorated = base.withWorkerCount(6)
                .withProfileName("ci")
                .withProjectModules(Set.of(dir))
                .withVariant("blue")
                .withClientEnv(Map.of("K", "v"))
                .withEphemeralActions(true)
                .withSiblings(gate);
        assertThat(base.siblings()).isSameAs(SiblingArtifacts.NONE);
        assertThat(decorated.siblings()).isSameAs(gate);
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

    @Test
    void an_absent_variant_or_client_env_reads_as_none() {
        Path dir = Path.of("/w/app");
        BuildPlanner.Inputs in =
                TestInputs.of(dir, Path.of("/cache")).withVariant(null).withClientEnv(null);
        assertThat(in.variant()).isEmpty();
        assertThat(in.clientEnv()).isEmpty();
    }
}
