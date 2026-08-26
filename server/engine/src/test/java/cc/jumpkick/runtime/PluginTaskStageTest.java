// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.run.BuildStage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plugin-task stage inference must agree with the edges {@link PlannerPlugin#pluginRequires} builds
 * — a plan the planner emits can never be one its own validator rejects. The Android task set is
 * the fixture because its manifest → res edge is what broke.
 */
class PluginTaskStageTest {

    /** Engine-declared task names and the stage each carries in a real module plan. */
    private static final Map<String, BuildStage> ENGINE_STAGES = Map.of(
            "parse-build", BuildStage.RESOLVE,
            "resolve-deps", BuildStage.RESOLVE,
            "ensure-jdk", BuildStage.RESOLVE,
            "build-logic-before-compile", BuildStage.GENERATE,
            "copy-resources", BuildStage.COMPILE,
            "run-tests", BuildStage.TEST,
            "package-jar", BuildStage.PACKAGE);

    private static PluginBuild.TaskDecl decl(
            String name, List<String> inputs, List<String> sources, List<String> testCp, String stage) {
        return new PluginBuild.TaskDecl(
                name, List.of(), inputs, List.of(), List.of(), List.of(), sources, testCp, null, stage);
    }

    /** The Android plugin's declared task set — the shape that regressed. */
    private static Map<String, PluginBuild.TaskDecl> androidDecls() {
        Map<String, PluginBuild.TaskDecl> m = new LinkedHashMap<>();
        m.put(
                "android-manifest",
                decl("android-manifest", List.of("project-files", "config"), List.of(), List.of(), null));
        m.put(
                "android-res",
                decl(
                        "android-res",
                        List.of("project-files", "step:android-manifest"),
                        List.of("gen"),
                        List.of(),
                        null));
        m.put(
                "android-test-config",
                decl(
                        "android-test-config",
                        List.of("step:android-manifest", "step:android-res"),
                        List.of(),
                        List.of("cp"),
                        null));
        m.put("android-dex", decl("android-dex", List.of("classes", "config"), List.of(), List.of(), null));
        return m;
    }

    /** Every edge a plugin task declares must point at an equal-or-earlier stage. */
    private static void assertEdgesValidate(Map<String, PluginBuild.TaskDecl> decls) {
        for (PluginBuild.TaskDecl step : decls.values()) {
            BuildStage from = PlannerPlugin.pluginStage(step);
            for (String req : PlannerPlugin.pluginRequires(step, null)) {
                BuildStage upstream = req.startsWith("plugin-")
                        ? PlannerPlugin.pluginStage(decls.get(req.substring("plugin-".length())))
                        : ENGINE_STAGES.get(req);
                assertThat(upstream)
                        .as("stage of %s (required by %s)", req, step.name())
                        .isNotNull();
                assertThat(from.mayRequire(upstream))
                        .as("plugin-%s (%s) requires %s (%s)", step.name(), from.wireName(), req, upstream.wireName())
                        .isTrue();
            }
        }
    }

    @Test
    void android_task_set_stages_agree_with_its_edges() {
        Map<String, PluginBuild.TaskDecl> decls = androidDecls();

        // android-manifest runs in the pre-compile window (no classes input, no contributions),
        // so it is `generate` — not `compile` by name, which is what made android-res illegal.
        assertThat(PlannerPlugin.pluginStage(decls.get("android-manifest"))).isEqualTo(BuildStage.GENERATE);
        assertThat(PlannerPlugin.pluginStage(decls.get("android-res"))).isEqualTo(BuildStage.GENERATE);
        assertThat(PlannerPlugin.pluginStage(decls.get("android-test-config"))).isEqualTo(BuildStage.TEST);
        assertThat(PlannerPlugin.pluginStage(decls.get("android-dex"))).isEqualTo(BuildStage.COMPILE);

        assertEdgesValidate(decls);
    }

    @Test
    void run_tests_contributors_never_land_downstream_of_test() {
        // A test-classpath contributor that also emits classes is not `testOnly`, so it rides the
        // compile window — run-tests (TEST) still requires it, so it may not claim a later stage.
        PluginBuild.TaskDecl step = new PluginBuild.TaskDecl(
                "boot-testjars",
                List.of(),
                List.of("classes"),
                List.of(),
                List.of("out"),
                List.of(),
                List.of(),
                List.of("cp"),
                null,
                null);
        assertThat(PlannerPlugin.pluginStage(step)).isEqualTo(BuildStage.COMPILE);
        assertThat(BuildStage.TEST.mayRequire(PlannerPlugin.pluginStage(step))).isTrue();
    }

    @Test
    void declared_stage_may_sharpen_the_fold_within_the_window() {
        PluginBuild.TaskDecl dex = decl("android-dex", List.of("classes", "config"), List.of(), List.of(), "package");
        assertThat(PlannerPlugin.pluginStage(dex)).isEqualTo(BuildStage.PACKAGE);
        assertThat(BuildStage.PACKAGE.mayRequire(BuildStage.COMPILE)).isTrue();
    }

    @Test
    void unknown_declared_stage_is_an_error_not_a_silent_other() {
        PluginBuild.TaskDecl typo = decl("mystery", List.of("config"), List.of(), List.of(), "packge");
        assertThatThrownBy(() -> PlannerPlugin.pluginStage(typo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mystery")
                .hasMessageContaining("packge")
                .hasMessageContaining("`package`");
    }

    @Test
    void declared_stage_earlier_than_the_window_names_the_plugin_task() {
        PluginBuild.TaskDecl bad = decl("late-codegen", List.of("classes"), List.of(), List.of(), "generate");
        assertThatThrownBy(() -> PlannerPlugin.pluginStage(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("late-codegen")
                .hasMessageContaining("compile window");
    }

    @Test
    void declared_stage_after_test_is_rejected_for_a_test_classpath_contributor() {
        PluginBuild.TaskDecl bad = decl("fixtures", List.of("config"), List.of(), List.of("cp"), "package");
        assertThatThrownBy(() -> PlannerPlugin.pluginStage(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixtures")
                .hasMessageContaining("run-tests");
    }

    @Test
    void declared_stage_after_package_is_rejected_for_a_package_time_task() {
        // package-jar (PACKAGE) requires every packageTime() task unconditionally
        // (PlannerPackage#packageRequires) — mirrors the run-tests/TEST case above for PACKAGE.
        PluginBuild.TaskDecl bad = decl("dex-native", List.of("classes", "config"), List.of(), List.of(), "native");
        assertThatThrownBy(() -> PlannerPlugin.pluginStage(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dex-native")
                .hasMessageContaining("package");
    }

    @Test
    void package_time_task_may_declare_up_to_package_stage() {
        PluginBuild.TaskDecl ok = decl("dex-native", List.of("classes", "config"), List.of(), List.of(), "package");
        assertThat(PlannerPlugin.pluginStage(ok)).isEqualTo(BuildStage.PACKAGE);
    }
}
