// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.protocol.BuildRequest;
import cc.jumpkick.engine.protocol.SingleBuildRequest;
import cc.jumpkick.runtime.WorkspaceRequest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Workspace request encoding: the session's resolved test selection must ride the wire.
 * Without it the engine falls back to each module's {@code [test]} excludes, and a widened run
 * ({@code --all} / {@code --include-tags}) is silently served from the unit-tier test stamp.
 */
class EngineJobsEncodeTest {

    private static WorkspaceRequest request() {
        return new WorkspaceRequest(
                Path.of("/proj"), Path.of("/cache"), null, 0, null, false, false, 0, null, true, true);
    }

    @Test
    void the_session_test_selection_rides_the_workspace_request() {
        TestSelection widened = TestSelection.of(List.of(), true, List.of("integration"), List.of("bench"), true);
        Session session = Session.defaults().withTestSelection(widened);

        String json = EngineJobs.encodeWorkspaceRequest(request(), session);

        assertThat(BuildRequest.decode(json).selection()).isEqualTo(widened);
        assertThat(json).contains("\"allSuites\":true").contains("\"includeTags\":[\"integration\"]");
    }

    @Test
    void the_single_build_request_carries_a_selection_too() {
        TestSelection widened = TestSelection.of(List.of(), true, List.of(), List.of(), true);

        String json =
                new SingleBuildRequest("/proj", "/cache", null, 0, null, false, false, false, false, widened).encode();

        assertThat(SingleBuildRequest.decode(json).selection()).isEqualTo(widened);
        assertThat(new SingleBuildRequest("/proj", "/cache", null, 0, null, false, false, false, false, null).encode())
                .doesNotContain("allSuites");
    }

    @Test
    void a_default_selection_is_omitted_so_older_engines_see_an_unchanged_body() {
        String json = EngineJobs.encodeWorkspaceRequest(request(), Session.defaults());

        assertThat(json).doesNotContain("includeTags").doesNotContain("allSuites");
        assertThat(BuildRequest.decode(json).selection()).isEqualTo(TestSelection.DEFAULT);
    }
}
