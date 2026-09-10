// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.wire.protocol.BuildRequest;
import cc.jumpkick.wire.protocol.SingleBuildRequest;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        String json = new SingleBuildRequest(
                        "/proj", "/cache", null, 0, null, false, false, false, false, null, widened)
                .encode();

        assertThat(SingleBuildRequest.decode(json).selection()).isEqualTo(widened);
        assertThat(new SingleBuildRequest("/proj", "/cache", null, 0, null, false, false, false, false, null, null)
                        .encode())
                .doesNotContain("allSuites");
    }

    /** A package build ships the client-resolved GraalVM home of every always-native module. */
    @Test
    void graal_homes_ride_the_package_build_request() {
        // The wire carries each path as the platform renders it, so the expectation is the same
        // rendering rather than one platform's spelling of it.
        Path appDir = Path.of("/proj/app");
        Path graalHome = Path.of("/jdks/graalvm-25");
        Map<Path, Path> homes = Map.of(appDir, graalHome);
        WorkspaceRequest req = request().withSpec(WorkspaceSpec.DEFAULT.withGraalByDir(homes));

        String json = EngineJobs.encodeWorkspaceRequest(req, Session.defaults());

        BuildRequest back = BuildRequest.decode(json);
        assertThat(back.workspaceTarget()).as("still a plain package build").isNull();
        assertThat(back.graalHomes()).containsEntry(appDir.toString(), graalHome.toString());
        assertThat(new SingleBuildRequest(
                                "/proj/app",
                                "/cache",
                                null,
                                0,
                                null,
                                false,
                                false,
                                false,
                                false,
                                "/jdks/graalvm-25",
                                null)
                        .encode())
                .contains("\"graalHome\":\"/jdks/graalvm-25\"");
    }

    /**
     * {@code --m2-dir} is the client's answer, like the Graal homes: a resident daemon does not
     * inherit the caller's environment, so an install spec that does not carry it has the engine
     * fall back to its <em>own</em> {@code ~/.m2} — which is how a redirected workspace install
     * came to write the real home repo.
     */
    @Test
    void the_m2_root_rides_the_install_request() {
        Path m2 = Path.of("/tmp/ws/m2");
        WorkspaceRequest req = request().withSpec(WorkspaceSpec.install(Set.of(), Map.of(), m2));

        BuildRequest back = BuildRequest.decode(EngineJobs.encodeWorkspaceRequest(req, Session.defaults()));

        assertThat(back.workspaceTarget()).isEqualTo("install");
        assertThat(back.m2Dir()).as("as the platform renders it").isEqualTo(m2.toString());
        // Default ~/.m2 stays off the wire, so the engine's own fallback still applies.
        WorkspaceRequest plain = request().withSpec(WorkspaceSpec.install(Set.of(), Map.of(), null));
        assertThat(EngineJobs.encodeWorkspaceRequest(plain, Session.defaults())).doesNotContain("m2Dir");
    }

    @Test
    void a_default_selection_is_omitted_so_older_engines_see_an_unchanged_body() {
        String json = EngineJobs.encodeWorkspaceRequest(request(), Session.defaults());

        assertThat(json).doesNotContain("includeTags").doesNotContain("allSuites");
        assertThat(BuildRequest.decode(json).selection()).isEqualTo(TestSelection.DEFAULT);
    }
}
