// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorkspaceExecute#requestKnobs} is the one spelling of the request-knob set. Both roads to
 * a module's {@link BuildPlanner.Inputs} — the decorate operator the NATIVE/IMAGE/COMPILE terminal
 * branches take, and {@link WorkspaceExecute#moduleInputs} on the PACKAGE/INSTALL path — must
 * deliver the request's knobs verbatim; a knob dropped from the owner is a module silently planned
 * against a different manifest than its siblings.
 */
class WorkspaceRequestKnobsTest {

    @TempDir
    Path tmp;

    @Test
    void the_decorate_branch_and_the_package_branch_carry_the_request_knobs_verbatim() throws Exception {
        Path mod = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(mod.resolve("jk.toml"), """
                [project]
                name = "app"
                group = "ex"
                version = "1.0"
                """);
        Set<Path> dirs = Set.of(mod);
        WorkspaceRequest req = new WorkspaceRequest(
                        tmp, tmp.resolve("cache"), null, 3, "fast", false, false, 0, null, true, true)
                .withVariant("release", Map.of("SECRET_HOME", "v"))
                .withEphemeralActions(true);

        // The operator handed to the NATIVE/IMAGE/COMPILE terminal branches, over neutral inputs…
        BuildPlanner.Inputs decorated = WorkspaceExecute.requestKnobs(req, dirs)
                .apply(TaskForecaster.inputsFor(mod, req.cache(), 1, null, null, false, false, Set.of(), false));
        // …and the PACKAGE/INSTALL path.
        BuildPlanner.Inputs packaged = WorkspaceExecute.moduleInputs(mod, req, dirs, false);

        for (BuildPlanner.Inputs in : List.of(decorated, packaged)) {
            assertThat(in.workerCount()).isEqualTo(3);
            assertThat(in.profileName()).isEqualTo("fast");
            assertThat(in.projectModules()).isEqualTo(dirs);
            assertThat(in.variant()).isEqualTo("release");
            assertThat(in.clientEnv()).isEqualTo(Map.of("SECRET_HOME", "v"));
            assertThat(in.ephemeralActions()).isTrue();
        }
    }

    @Test
    void zero_workers_plans_as_one_on_both_paths() throws Exception {
        Path mod = Files.createDirectories(tmp.resolve("app"));
        Set<Path> dirs = Set.of(mod);
        WorkspaceRequest req =
                new WorkspaceRequest(tmp, tmp.resolve("cache"), null, 0, null, false, false, 0, null, true, true);

        BuildPlanner.Inputs decorated = WorkspaceExecute.requestKnobs(req, dirs)
                .apply(TaskForecaster.inputsFor(mod, req.cache(), 1, null, null, false, false, Set.of(), false));
        BuildPlanner.Inputs packaged = WorkspaceExecute.moduleInputs(mod, req, dirs, false);

        assertThat(decorated.workerCount()).isEqualTo(1);
        assertThat(packaged.workerCount()).isEqualTo(1);
    }
}
