// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenEventsTest {

    @TempDir
    Path tmp;

    @Test
    void a_skipped_module_and_a_compile_failure_fold_in_reactor_order() throws Exception {
        Path file = tmp.resolve("events.tsv");
        Files.writeString(
                file,
                String.join(
                        "\n",
                        MavenRunFixture.line("ProjectStarted", "0", "g:lib", "/ws/lib", "", "", "", ""),
                        MavenRunFixture.line(
                                "MojoStarted",
                                "0",
                                "g:lib",
                                "/ws/lib",
                                "maven-compiler-plugin:compile",
                                "default-compile",
                                "",
                                ""),
                        MavenRunFixture.line(
                                "MojoFailed",
                                "0",
                                "g:lib",
                                "/ws/lib",
                                "maven-compiler-plugin:compile",
                                "default-compile",
                                "x.CompilationFailureException",
                                "/ws/lib/A.java:[1,2] boom"),
                        MavenRunFixture.line(
                                "ProjectFailed", "40", "g:lib", "/ws/lib", "", "", "", "Failed to execute goal"),
                        MavenRunFixture.line("ProjectSkipped", "0", "g:app", "/ws/app", "", "", "", ""),
                        "not\ta\tline",
                        ""));
        List<MavenEvents.Module> modules = MavenEvents.modules(MavenEvents.read(file));
        assertThat(modules).hasSize(2);
        MavenEvents.Module lib = modules.get(0);
        assertThat(lib.coord()).isEqualTo("g:lib");
        assertThat(lib.outcome()).isEqualTo("FAIL");
        assertThat(lib.millis()).isEqualTo(40);
        assertThat(lib.steps()).containsExactly(new MavenEvents.Step("compiler:compile", "FAIL"));
        // The mojo's failure, not the lifecycle's wrapper, is the one the diagnostics read.
        MavenEvents.Failure failure = Objects.requireNonNull(lib.failure(), "lib failed");
        assertThat(failure.goal()).isEqualTo("compiler:compile");
        assertThat(failure.message()).isEqualTo("/ws/lib/A.java:[1,2] boom");
        MavenEvents.Module app = modules.get(1);
        assertThat(app.skipped()).isTrue();
        assertThat(app.success()).isFalse();
        assertThat(app.steps()).isEmpty();
    }

    @Test
    void goals_shorten_to_mavens_log_prefix() {
        assertThat(MavenEvents.shortGoal("maven-compiler-plugin:compile")).isEqualTo("compiler:compile");
        assertThat(MavenEvents.shortGoal("spring-boot-maven-plugin:repackage")).isEqualTo("spring-boot:repackage");
        assertThat(MavenEvents.shortGoal("kotlin-maven-plugin:compile")).isEqualTo("kotlin:compile");
        assertThat(MavenEvents.shortGoal("odd")).isEqualTo("odd");
    }
}
