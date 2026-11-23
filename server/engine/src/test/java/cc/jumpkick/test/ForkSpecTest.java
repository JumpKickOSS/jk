// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The launcher starts every JVM from its fork spec: the spec's flags around the module's own JVM
 * arguments and its selection arguments, each of which the spec's stamp token spells.
 */
class ForkSpecTest {

    private static final ForkSpec SPEC = new ForkSpec(
            true,
            false,
            0,
            false,
            List.of(),
            List.of("fast"),
            List.of("slow"),
            List.of("com.acme.FooTest#adds"),
            List.of("*PerfTest"),
            true);

    @Test
    void every_jvm_starts_with_the_specs_flags_around_the_modules_own() {
        JUnitLauncher launcher = new JUnitLauncher().withFork(SPEC).withJvmArgs(List.of("-Dmodule=1"));
        List<String> expected = new ArrayList<>(SPEC.leading());
        expected.add("-Dmodule=1");
        expected.addAll(SPEC.trailing());

        for (JvmRole role : JvmRole.values()) {
            assertThat(launcher.jvmFlags(role, 2, null)).as(role.name()).containsSubsequence(expected);
        }
    }

    @Test
    void the_runner_gets_the_specs_selection_and_the_settled_classes_left_out() {
        List<String> args = new JUnitLauncher()
                .withFork(SPEC)
                .withSettled(List.of("com.acme.DoneTest"))
                .pullWorkerArgs(1, Path.of("classes"));

        assertThat(args).containsAll(SPEC.tagArgs(List.of())).containsAll(SPEC.methodArgs());
        assertThat(args).anyMatch(a -> a.startsWith("--filter=") && a.contains("DoneTest") && a.contains("PerfTest"));
    }

    @Test
    void the_token_spells_what_the_launch_adds() {
        String token = SPEC.token();

        for (String flag : SPEC.leading()) assertThat(token).contains(flag);
        for (String flag : SPEC.trailing()) assertThat(token).contains(flag);
        for (String arg : SPEC.tagArgs(List.of())) assertThat(token).contains(arg);
        SPEC.env().forEach((name, value) -> assertThat(token).contains("env:" + name + "=" + value));
        assertThat(token).contains("include=" + SPEC.classFilter());
        assertThat(token)
                .as("settled classes are the ledger's, under the same stamp")
                .doesNotContain("DoneTest");
    }

    @Test
    void the_settings_it_carries_each_move_the_token() {
        String base = ForkSpec.DEFAULT.token();
        List<ForkSpec> moved = List.of(
                new ForkSpec(false, false, 0, false, List.of(), List.of(), List.of(), List.of(), List.of(), false),
                new ForkSpec(true, true, 0, false, List.of(), List.of(), List.of(), List.of(), List.of(), false),
                new ForkSpec(true, false, 5, false, List.of(), List.of(), List.of(), List.of(), List.of(), false),
                new ForkSpec(true, false, 0, true, List.of(), List.of(), List.of(), List.of(), List.of(), false),
                new ForkSpec(true, false, 0, false, List.of("db"), List.of(), List.of(), List.of(), List.of(), false),
                new ForkSpec(true, false, 0, false, List.of(), List.of(), List.of(), List.of(), List.of("X"), false),
                new ForkSpec(true, false, 0, false, List.of(), List.of(), List.of(), List.of(), List.of(), true));
        for (ForkSpec spec : moved) assertThat(spec.token()).as(spec.toString()).isNotEqualTo(base);
    }
}
