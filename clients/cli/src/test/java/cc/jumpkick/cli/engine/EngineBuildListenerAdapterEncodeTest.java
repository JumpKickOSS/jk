// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.runtime.WorkspaceRequest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Workspace request encoding: the session's resolved test selection must ride the wire (JK-2181).
 * Without it the engine falls back to each module's {@code [test]} excludes, and a widened run
 * ({@code --all} / {@code --include-tags}) is silently served from the unit-tier test stamp.
 */
class EngineBuildListenerAdapterEncodeTest {

    private static WorkspaceRequest request() {
        return new WorkspaceRequest(
                Path.of("/proj"), Path.of("/cache"), null, 0, null, false, false, 0, null, true, true);
    }

    @Test
    void the_session_test_selection_rides_the_workspace_request() {
        TestSelection widened = TestSelection.of(List.of(), true, List.of("integration"), List.of("bench"), true);
        Session session = Session.defaults().withTestSelection(widened);

        String json = EngineBuildListenerAdapter.encodeWorkspaceRequest(request(), session);

        assertThat(ProtoJobs.testSelectionOf(json)).isEqualTo(widened);
        assertThat(json).contains("\"allSuites\":true").contains("\"includeTags\":[\"integration\"]");
    }

    @Test
    void the_single_build_request_carries_a_selection_too() {
        TestSelection widened = TestSelection.of(List.of(), true, List.of(), List.of(), true);

        String json =
                ProtoJobs.singleBuildRequest("/proj", "/cache", null, 0, null, false, false, false, false, widened);

        assertThat(ProtoJobs.testSelectionOf(json)).isEqualTo(widened);
        assertThat(ProtoJobs.singleBuildRequest("/proj", "/cache", null, 0, null, false, false, false, false))
                .doesNotContain("allSuites");
    }

    @Test
    void a_default_selection_is_omitted_so_older_engines_see_an_unchanged_body() {
        String json = EngineBuildListenerAdapter.encodeWorkspaceRequest(request(), Session.defaults());

        assertThat(json).doesNotContain("includeTags").doesNotContain("allSuites");
        assertThat(ProtoJobs.testSelectionOf(json)).isEqualTo(TestSelection.DEFAULT);
    }
}
