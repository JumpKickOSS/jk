// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.wire.protocol.BuildRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --no-profile} reaches the engine as {@link Profiles#NO_PROFILE} and turns the whole profile
 * off there: on CI, where the {@code ci} profile would otherwise be selected, its tags, {@code env},
 * {@code jvm-args} and {@code javac} all stay out, and nothing names the run after it.
 */
class NoProfileTest {

    private static JkBuild project(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                name = "demo"
                group = "t"
                version = "0.0.1"
                java = 25

                [test]
                jvm-args = ["-Dbase=1"]

                [profiles.ci]
                exclude-tags = ["slow"]
                env = { CI_ONLY = "1" }
                jvm-args = ["-Dci=1"]
                javac = ["-Werror"]
                """);
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }

    private static <T> T onCi(Callable<T> body) throws Exception {
        return SessionContext.where(Session.defaults().withVariant("", Map.of("CI", "true")), body);
    }

    @Test
    void on_ci_the_auto_selected_profile_applies_without_the_flag(@TempDir Path dir) throws Exception {
        JkBuild project = project(dir);
        assertThat(onCi(() -> CompileSupport.activeProfileName(null))).isEqualTo("ci");
        assertThat(onCi(() -> PlannerSupport.testJvmArgs(project, null))).containsExactly("-Dbase=1", "-Dci=1");
        assertThat(onCi(() -> TestLaunch.profileEnv(project, null))).containsEntry("CI_ONLY", "1");
    }

    @Test
    void no_profile_turns_off_env_jvm_args_javac_and_tags(@TempDir Path dir) throws Exception {
        JkBuild project = project(dir);
        String none = Profiles.NO_PROFILE;
        assertThat(onCi(() -> CompileSupport.activeProfileName(none))).isNull();
        assertThat(onCi(() -> CompileSupport.resolveProfile(project.profiles(), none)))
                .isNull();
        assertThat(onCi(() -> PlannerSupport.testJvmArgs(project, none))).containsExactly("-Dbase=1");
        assertThat(onCi(() -> TestLaunch.profileEnv(project, none))).isEmpty();
        assertThat(onCi(() -> SuiteEnvKeys.profileEnv(project, none))).isEmpty();
        assertThat(onCi(() -> PlannerSupport.effectiveSelection(TestSelection.DEFAULT, dir, none)
                        .excludeTags()))
                .isEmpty();
        assertThat(onCi(() -> PlannerSupport.effectiveSelection(TestSelection.DEFAULT, dir, null)
                        .excludeTags()))
                .containsExactly("slow");
    }

    @Test
    void nothing_names_the_run_after_the_marker() {
        var sel = TestSelection.of(List.of(), false, List.of("integration"), List.of(), true);
        assertThat(TestTier.of(sel, Profiles.NO_PROFILE, 0).name()).isEqualTo("integration");
        assertThat(TestClassMatch.noMatchMessage(
                        TestSelection.DEFAULT.withClasses(List.of("FooTest")), Profiles.NO_PROFILE, null, null))
                .doesNotContain(Profiles.NO_PROFILE);
    }

    @Test
    void the_marker_rides_the_build_requests_profile_field() {
        var req = new BuildRequest(
                null,
                null,
                null,
                0,
                Profiles.NO_PROFILE,
                false,
                false,
                0,
                true,
                false,
                false,
                false,
                false,
                false,
                null,
                TestSelection.DEFAULT,
                null,
                List.of(),
                false,
                null,
                Map.of(),
                null,
                null,
                null);
        assertThat(BuildRequest.decode(req.encode()).profile()).isEqualTo(Profiles.NO_PROFILE);
    }
}
