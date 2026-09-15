// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ToolchainInstallsTest {

    private static final InstalledJdk JDK = new InstalledJdk("temurin-21.0.5", Path.of("/opt/jdks/temurin-21.0.5"));

    @Test
    void a_warning_from_the_install_reaches_the_plan_and_the_human_stream() {
        String[] err = new String[1];
        String out = Capture.stdout(() -> err[0] = Capture.stderr(() -> {
            CliOutput.beginCommand(true);
            ToolchainInstalls.run(BuildPlanConsole.Mode.JSON, "why", "Temurin 21", (progress, warn) -> {
                warn.accept("JDK feed unreachable, using cached feed");
                return JDK;
            });
        }));

        assertThat(out.lines())
                .allSatisfy(line -> assertThat(line).startsWith("{").endsWith("}"));
        assertThat(out).contains("\"type\":\"warn\"").contains("JDK feed unreachable");
        assertThat(err[0]).contains("JDK feed unreachable");
    }

    @Test
    void an_interrupted_install_re_interrupts_the_thread_and_names_it_on_the_step() {
        RecordingContext ctx = new RecordingContext();

        RuntimeException failure = ToolchainInstalls.failure(ctx, new InterruptedException());

        assertThat(Thread.interrupted()).as("interrupt flag re-raised").isTrue();
        assertThat(failure).hasCauseInstanceOf(InterruptedException.class);
        assertThat(ctx.errors).containsExactly("jdk: interrupted");
    }

    @Test
    void any_other_failure_is_recorded_with_its_text() {
        RecordingContext ctx = new RecordingContext();

        ToolchainInstalls.failure(ctx, new IOException("no JDK matches zulu-21 on linux/x86_64"));

        assertThat(Thread.interrupted()).isFalse();
        assertThat(ctx.errors).containsExactly("jdk: no JDK matches zulu-21 on linux/x86_64");
    }

    /** A TaskContext that keeps the step's diagnostics and nothing else. */
    private static final class RecordingContext implements TaskContext {
        final List<String> errors = new ArrayList<>();

        @Override
        public void progress(int delta) {}

        @Override
        public void updateTicks(int additional) {}

        @Override
        public void label(@Nullable String description) {}

        @Override
        public void output(@Nullable String line) {}

        @Override
        public void warn(String code, String message) {}

        @Override
        public void error(String code, String message) {
            errors.add(code + ": " + message);
        }

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public <T> void put(BuildPlanKey<T> key, T value) {}

        @Override
        public <T> Optional<T> get(BuildPlanKey<T> key) {
            return Optional.empty();
        }

        @Override
        public <T> T require(BuildPlanKey<T> key) {
            throw new IllegalStateException(key.toString());
        }
    }
}
