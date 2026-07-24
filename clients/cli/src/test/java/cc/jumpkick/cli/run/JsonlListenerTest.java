// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.PipelineView;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Aggregate-rider discipline (JK-1121): a single-pipeline listener owns the rider; a workspace
 * member must never stamp its module-local fraction over the engine's workspace aggregate.
 */
class JsonlListenerTest {

    @AfterEach
    void clear() {
        LiveProgress.get().clear();
    }

    @Test
    void single_pipeline_listener_updates_aggregate_rider() {
        LiveProgress.get().clear();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JsonlListener lis = new JsonlListener(new PrintStream(buf, true, StandardCharsets.UTF_8));
        lis.progress("compile", 1, new PipelineView("build", 50, 100, 3, 1, false));
        assertThat(LiveProgress.get().percent()).isEqualTo(50.0);
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("\"type\":\"progress\"");
    }

    @Test
    void workspace_member_listener_never_clobbers_engine_aggregate() {
        LiveProgress.get().setPercent(80.0); // engine workspace-progress snapshot already applied
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JsonlListener lis = new JsonlListener(new PrintStream(buf, true, StandardCharsets.UTF_8), false);
        lis.progress("compile", 1, new PipelineView("module-a", 1, 10, 3, 1, false));
        lis.tickUpdate("compile", 1, new PipelineView("module-a", 2, 10, 3, 1, false));
        // Module-local 10%/20% must not drag the aggregate rider backwards.
        assertThat(LiveProgress.get().percent()).isEqualTo(80.0);
        // Events still carry their pipeline-local numerator/denominator + the engine rider.
        String out = buf.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("\"numerator\":1,\"denominator\":10");
        assertThat(out).contains("\"progress\":80");
    }
}
