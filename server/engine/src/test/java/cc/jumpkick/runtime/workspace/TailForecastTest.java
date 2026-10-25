// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.lock.LockManifestDigest;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.wire.runtime.TailArgs;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A goal past PACKAGE schedules a module its lifecycle left clean exactly when a step of the
 * goal's tail, in that module's own plan, runs: a side effect always does, a priced step does
 * when its cache cannot vouch for it, and a module whose plan holds no tail never is.
 */
class TailForecastTest {

    @TempDir
    Path tmp;

    private Path root;
    private Path app;
    private Path lib;

    @BeforeEach
    void workspace() throws Exception {
        root = tmp.toRealPath();
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["app", "lib"]
                """);
        app = module("app", "public static void main(String[] a) {}");
        lib = module("lib", "");
        Files.writeString(
                lib.resolve("jk.toml"), Files.readString(lib.resolve("jk.toml")) + "\n[native]\nenabled = true\n");
        Lockfile lf = new Lockfile(
                Lockfile.CURRENT_VERSION, "test", "pubgrub-v1", null, null, List.of(), List.of(), List.of());
        LockfileWriter.write(lf, root.resolve("jk-lock.toml"), LockManifestDigest.compute(root));
    }

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void a_goal_without_a_tail_schedules_only_what_its_lifecycle_found_dirty() throws Exception {
        assertThat(scheduled(WorkspaceSpec.of(WorkspaceTarget.PACKAGE))).isEmpty();
        assertThat(scheduled(WorkspaceSpec.of(WorkspaceTarget.TEST))).isEmpty();
        assertThat(scheduled(WorkspaceSpec.compile(Set.of()))).isEmpty();
    }

    @Test
    void an_image_is_written_however_clean_its_module_and_only_for_the_selection() throws Exception {
        assertThat(scheduled(WorkspaceSpec.image(Set.of(app), null, null, null, null, null)))
                .containsExactly(app);
    }

    @Test
    void publish_and_train_run_their_tail_on_a_clean_tree() throws Exception {
        TailArgs.Publish dryRun = new TailArgs.Publish(
                null,
                null,
                null,
                null,
                false,
                true,
                null,
                null,
                false,
                false,
                false,
                RepoCredential.ANONYMOUS,
                false,
                null);
        assertThat(scheduled(WorkspaceSpec.publish(Set.of(lib), dryRun))).containsExactly(lib);
        assertThat(scheduled(WorkspaceSpec.train(Set.of(app), Map.of(), null, false)))
                .containsExactly(app);
    }

    /** A native image with no record runs; a module without a GraalVM home has no native tail. */
    @Test
    void a_native_tail_is_priced_where_the_plan_holds_one() throws Exception {
        assertThat(scheduled(WorkspaceSpec.nativeImage(Set.of(app), Map.of(), null, List.of())))
                .as("no GraalVM home resolved: the plan has no native tail")
                .isEmpty();
        assertThat(scheduled(
                        WorkspaceSpec.nativeImage(Set.of(app), Map.of(app, root.resolve("graal")), null, List.of())))
                .containsExactly(app);
    }

    @Test
    void an_install_schedules_the_modules_whose_bytes_are_not_shelved() throws Exception {
        assertThat(scheduled(WorkspaceSpec.install(Set.of(), Map.of(), root.resolve("m2"), null)))
                .containsExactlyInAnyOrder(app, lib);
    }

    @Test
    void a_reshelve_reshelves_every_member() throws Exception {
        assertThat(scheduled(WorkspaceSpec.reshelve(Set.of(), root.resolve("m2"), null)))
                .containsExactlyInAnyOrder(app, lib);
    }

    private Set<Path> scheduled(WorkspaceSpec spec) throws Exception {
        BuildGraph.Result graph = BuildGraph.resolve(root, JkBuildParser.parse(root.resolve("jk.toml")));
        assertThat(graph.hasErrors()).as("%s", graph.errors()).isFalse();
        List<BuildGraph.BuildUnit> units = graph.topoOrder();
        Set<Path> moduleDirs = Set.of(root, app, lib);
        WorkspaceRequest request = new WorkspaceRequest(
                        root, root.resolve("cache"), null, 0, null, false, false, 0, Set.of(), true, true)
                .withSpec(spec);
        return SessionContext.where(
                Session.defaults(),
                () -> WorkspacePreflightPhase.withRunningTails(
                        Set.of(), units, request, moduleDirs, Set.of(), Set.of()));
    }

    private Path module(String name, String body) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name)).toRealPath();
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "%s"
                version = "0.1.0"
                java = 25
                """.formatted(name));
        Path src = Files.createDirectories(dir.resolve("src/main/java/t"));
        String type = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Files.writeString(src.resolve(type + ".java"), "package t; public class " + type + " { " + body + " }\n");
        return dir;
    }
}
