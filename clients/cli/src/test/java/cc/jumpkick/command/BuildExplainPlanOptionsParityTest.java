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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * HARD INVARIANT, client half: {@code jk build} and {@code jk explain} feed the shared ETA/forecast
 * the same plan-affecting options.
 *
 * <p>{@code ExplainBuildEtaParityTest} over in {@code :engine} pins the shared <em>function</em>,
 * and says in its own comment that it cannot reach this half — {@code BuildCommand} and {@code
 * ExplainCommand} live here. This is that half. It catches both ways the two can drift:
 *
 * <ul>
 *   <li><b>Declaration drift</b> — a plan-affecting flag added to one command's {@code options()}
 *       and not the other. The other command's parse then rejects the argv outright.
 *   <li><b>Derivation drift</b> — the same flag read into a different value. Both commands go
 *       through {@link PlanOptions#from}, and this asserts they land on the same record.
 * </ul>
 *
 * <p>Two real regressions this locks down: {@code jk build} floored a negative {@code -w} to
 * {@code 0} while {@code jk explain} passed it straight through, and {@code jk build} turned
 * {@code --scripts-only} into {@code skipTests} while {@code jk explain} did not — so
 * {@code jk explain --scripts-only} priced test suites the build would never run.
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
        return Stream.of(
                List.of(),
                List.of("--skip-tests"),
                List.of("--scripts-only"),
                List.of("--no-scripts"),
                List.of("--guard"),
                List.of("-w", "4"),
                List.of("-w", "0"),
                // Reachable only through the `=` form (bare `-w -3` parses -3 as an option), and
                // `jk build` floored it while `jk explain` passed it straight through.
                List.of("--workers=-3"),
                List.of("--serial-tests"),
                List.of("--profile", "ci"),
                List.of("--no-profile"),
                List.of("-j", "1"),
                List.of("-j", "8"),
                List.of("--redo"),
                List.of("--force"),
                List.of("--verbose"),
                List.of("--all"),
                List.of("--include-tags", "fast"),
                List.of("--exclude-tags", "slow"),
                List.of("--suite", "integration"),
                // The combination a real invocation looks like.
                List.of("-j", "4", "-w", "2", "--profile", "ci", "--serial-tests", "--exclude-tags", "slow"));
    }

    @ParameterizedTest(name = "jk {0}")
    @MethodSource("plan_affecting_argv")
    void build_and_explain_derive_the_same_plan_options(List<String> argv) {
        assertThat(planOptions(new ExplainCommand(), argv))
                .as("jk explain %s must forecast what jk build %s would run", argv, argv)
                .isEqualTo(planOptions(new BuildCommand(), argv));
    }

    /** Every flag above is one both commands accept — a flag on only one of them fails here. */
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
        List<String> full = new ArrayList<>(List.of("-C", project.toAbsolutePath().toString()));
        full.addAll(argv);
        try {
            // Parse exactly as dispatch does: the command's options plus the shared globals.
            return ArgParser.parse(CommandDispatch.withGlobals(command), full);
        } catch (Exception e) {
            throw new AssertionError(command.getClass().getSimpleName() + " rejected " + full, e);
        }
    }
}
