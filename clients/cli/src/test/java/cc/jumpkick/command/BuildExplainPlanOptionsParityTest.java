// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cc.jumpkick.cli.CommandDispatch;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PlanOptions;
import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.command.pipeline.BuildCommand;
import cc.jumpkick.command.pipeline.TestCommand;
import cc.jumpkick.command.project.ExplainCommand;
import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code jk build} and {@code jk explain} feed the shared forecast the same plan-affecting options.
 * The argv under test is derived from {@link PlanOptions#options()} itself — every name of every
 * option, valued ones in both spellings — so a flag added there is exercised against both commands
 * without anyone remembering to list it here.
 */
class BuildExplainPlanOptionsParityTest {

    /**
     * A workspace with a guard script, because {@code --scripts-only} is rejected outright by
     * {@code TestCommand.resolveTestSelection} on a project that declares none.
     */
    @TempDir
    static Path project;

    static Stream<List<String>> plan_affecting_argv() throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"parity\"\ngroup = \"demo\"\nversion = \"0.1.0\"\n");
        Files.createDirectories(project.resolve(BuildLogicToml.VISIBLE_DIR));
        Files.writeString(
                project.resolve(BuildLogicToml.VISIBLE_DIR).resolve("guard.kts"),
                "// a guard script, so --scripts-only resolves\n");
        List<List<String>> cases = new ArrayList<>();
        cases.add(List.of());
        for (Opt opt : PlanOptions.options()) {
            for (String name : opt.allNames()) {
                if (!opt.takesValue()) {
                    cases.add(List.of(name));
                    continue;
                }
                String value = sampleValue(opt.canonicalName());
                cases.add(List.of(name, value));
                if (name.startsWith("--")) cases.add(List.of(name + "=" + value));
            }
        }
        // Reachable only through the `=` form (bare `-w -3` parses -3 as an option); both floor it.
        cases.add(List.of("--workers=-3"));
        // The globals PlanOptions.from reads.
        cases.add(List.of("-j", "1"));
        cases.add(List.of("-j", "8"));
        cases.add(List.of("--redo"));
        cases.add(List.of("--force"));
        cases.add(List.of("--verbose"));
        // The combination a real invocation looks like.
        cases.add(List.of("-j", "4", "-w", "2", "--profile", "ci", "--serial-tests", "--exclude-tags", "slow"));
        return cases.stream();
    }

    /** One value per valued option; a new one must be given a sample here or the derivation fails loudly. */
    private static String sampleValue(String option) {
        return switch (option) {
            case "profile" -> "ci";
            case "workers" -> "4";
            case "suite" -> "integration";
            case "include-tags" -> "fast";
            case "exclude-tags" -> "slow";
            case "jdks-dir" -> project.resolve("jdks").toString();
            default -> throw new AssertionError("no sample value for --" + option);
        };
    }

    @ParameterizedTest(name = "jk {0}")
    @MethodSource("plan_affecting_argv")
    void build_and_explain_derive_the_same_plan_options(List<String> argv) {
        assertThat(planOptions(new ExplainCommand(), argv))
                .as("jk explain %s must forecast what jk build %s would run", argv, argv)
                .isEqualTo(planOptions(new BuildCommand(), argv));
    }

    /** Every derived argv is one both commands accept — an option on only one of them fails here. */
    @ParameterizedTest(name = "jk {0}")
    @MethodSource("plan_affecting_argv")
    void both_commands_accept_every_plan_affecting_flag(List<String> argv) {
        assertThatCode(() -> parse(new BuildCommand(), argv)).doesNotThrowAnyException();
        assertThatCode(() -> parse(new ExplainCommand(), argv)).doesNotThrowAnyException();
    }

    private static PlanOptions planOptions(CliCommand command, List<String> argv) {
        Invocation in = parse(command, argv);
        return PlanOptions.from(in, GlobalOptions.from(in), TestCommand.resolveTestSelection(in));
    }

    private static Invocation parse(CliCommand command, List<String> argv) {
        List<String> full =
                new ArrayList<>(List.of("-C", project.toAbsolutePath().toString()));
        full.addAll(argv);
        try {
            // Parse exactly as dispatch does: the command's options plus the shared globals.
            return ArgParser.parse(CommandDispatch.withGlobals(command), full);
        } catch (Exception e) {
            throw new AssertionError(command.getClass().getSimpleName() + " rejected " + full, e);
        }
    }
}
