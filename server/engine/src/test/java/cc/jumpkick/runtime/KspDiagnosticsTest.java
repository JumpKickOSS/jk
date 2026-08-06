// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import org.junit.jupiter.api.Test;

/**
 * a successful KSP round's processor diagnostics must reach the user. They used to be
 * dropped on the floor unless the build failed, so non-fatal processor guidance only ever appeared
 * once something was already broken.
 */
class KspDiagnosticsTest {

    @Test
    void processor_warnings_are_surfaced_with_their_severity() {
        String output = """
                w: [ksp] [KNEST-W021] Task.title has no @Length; defaulting to varchar(255)
                w: [ksp] generated 3 tables
                """;
        assertThat(BuildPlanner.kspDiagnostics(output))
                .extracting(BuildPlanner.KspDiagnostic::severity, BuildPlanner.KspDiagnostic::message)
                .containsExactly(
                        tuple("warn", "[KNEST-W021] Task.title has no @Length; defaulting to varchar(255)"),
                        tuple("warn", "generated 3 tables"));
    }

    @Test
    void the_severity_and_ksp_prefixes_are_stripped_from_the_message() {
        // The reporter renders step + severity itself; keeping them would print
        // `Warning [ksp/ksp]: w: [ksp] …`.
        var diagnostics = BuildPlanner.kspDiagnostics("w: [ksp] plain text\n");
        assertThat(diagnostics).singleElement().satisfies(d -> {
            assertThat(d.severity()).isEqualTo("warn");
            assertThat(d.message()).isEqualTo("plain text");
        });
    }

    @Test
    void jvm_host_noise_is_filtered_out() {
        // KSP2's bundled IntelliJ containers trigger these on JDK 24+ every single run; echoing
        // them on every green build would train people to ignore the channel.
        String output = """
                WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
                WARNING: sun.misc.Unsafe::objectFieldOffset has been called by ksp.com.intellij.util.containers.Unsafe
                WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release
                w: [ksp] a real diagnostic
                """;
        assertThat(BuildPlanner.kspDiagnostics(output))
                .extracting(BuildPlanner.KspDiagnostic::message)
                .containsExactly("a real diagnostic");
    }

    @Test
    void kotlinc_warnings_without_the_ksp_tag_are_left_to_the_compile_step() {
        String output = "w: file:///p/Foo.kt:3:9 variable is never used\nw: [ksp] mine\n";
        assertThat(BuildPlanner.kspDiagnostics(output))
                .extracting(BuildPlanner.KspDiagnostic::message)
                .containsExactly("mine");
    }

    @Test
    void info_and_verbose_levels_are_carried_with_distinct_severities() {
        String output = "i: [ksp] loaded 2 providers\nv: [ksp] round 1\n";
        assertThat(BuildPlanner.kspDiagnostics(output))
                .extracting(BuildPlanner.KspDiagnostic::severity, BuildPlanner.KspDiagnostic::message)
                .containsExactly(tuple("info", "loaded 2 providers"), tuple("verbose", "round 1"));
    }

    @Test
    void blank_untagged_and_empty_message_output_yields_nothing() {
        assertThat(BuildPlanner.kspDiagnostics("")).isEmpty();
        assertThat(BuildPlanner.kspDiagnostics("\n\n   \n")).isEmpty();
        assertThat(BuildPlanner.kspDiagnostics("Note: some javac chatter\n")).isEmpty();
        assertThat(BuildPlanner.kspDiagnostics("w: [ksp]   \n")).isEmpty();
    }
}
