// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.KeyAuditMode;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.BuildService;
import cc.jumpkick.runtime.workspace.ExplainReport;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.InputGuard;
import cc.jumpkick.task.KeyAudit;
import cc.jumpkick.wire.runtime.BuildKnobs;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The invariant every e2e fixture owes the next build: the second build of an unchanged tree runs
 * nothing. The first build runs under {@code JK_KEY_AUDIT=strict}, so a step whose key
 * moved because of what the build wrote fails it, naming the step and the token; then {@code jk
 * explain} forecasts nothing; then the second build runs no productive step and every module it
 * entered reads up to date. A failure names the steps that ran with what they said.
 *
 * <p>Give each fixture content no other test's cache has seen (its own cache, or a nonce in a source):
 * a step that keys build 2 differently from build 1 hits on build 2 when an earlier run stored both
 * keys, and the defect hides.
 */
final class SecondBuild {

    private SecondBuild() {}

    /** As {@link #assertSecondBuildRunsNothing(Path, Path, BuildKnobs)} for {@code jk build}. */
    static void assertSecondBuildRunsNothing(Path ws, Path cache) throws Exception {
        assertSecondBuildRunsNothing(ws, cache, BuildKnobs.DEFAULTS);
    }

    /** Build {@code ws} under {@code knobs} audited, explain it, build it again; see the class comment. */
    static void assertSecondBuildRunsNothing(Path ws, Path cache, BuildKnobs knobs) throws Exception {
        Observed first = build(ws, cache, knobs);
        assertThat(first.result.success())
                .as(
                        "the first build, audited: %s%nkey-moved and inputs-moved: %s%nstep errors: %s",
                        first.result.errors(), first.moved, first.errors)
                .isTrue();
        assertThat(first.moved).as("no key moved in the first build").isEmpty();

        if (knobs.target().packages()) {
            ExplainReport report = ExplainReport.compute(
                    ws, JkBuildParser.parse(ws.resolve("jk.toml")), cache, session(cache, knobs), knobs, 0);
            assertThat(report.plan().errors()).as("explain errors").isEmpty();
            assertThat(dirty(report.plan().modules()))
                    .as("jk explain right after the first build forecasts nothing")
                    .isEmpty();
        }

        Observed second = build(ws, cache, knobs);
        assertThat(second.result.success())
                .as("the second build: %s%nstep errors: %s", second.result.errors(), second.errors)
                .isTrue();
        assertThat(second.ran)
                .as("steps the second build of an unchanged tree ran (step: what it said)")
                .isEmpty();
        assertThat(second.result.modules().stream()
                        .filter(ModuleOutcome::didWork)
                        .map(o -> o.dir().getFileName().toString())
                        .toList())
                .as("modules the second build reports built rather than up to date; ran: %s", second.ran)
                .isEmpty();
        assertThat(second.moved)
                .as("key-moved and inputs-moved in the second build")
                .isEmpty();
    }

    /**
     * A comment no other run wrote at the end of every JVM source under {@code ws} outside {@code
     * target/}: content a shared cache has never seen, so a key build 2 takes differently cannot hit
     * a record an earlier run stored.
     */
    static void nonce(Path ws) throws IOException {
        String line = "\n// nonce " + UUID.randomUUID() + "\n";
        try (Stream<Path> walk = Files.walk(ws)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString();
                if (ws.relativize(f).toString().contains("target")) continue;
                if (name.endsWith(".java")
                        || name.endsWith(".kt")
                        || name.endsWith(".groovy")
                        || name.endsWith(".scala")) {
                    Files.writeString(f, line, StandardOpenOption.APPEND);
                }
            }
        }
    }

    /** {@code from}'s files outside {@code target/} copied under {@code to}, which is returned. */
    static Path copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path f : walk.toList()) {
                Path rel = from.relativize(f);
                if (rel.toString().startsWith("target")) continue;
                Path dest = to.resolve(rel.toString());
                if (Files.isDirectory(f)) Files.createDirectories(dest);
                else Files.copy(f, dest);
            }
        }
        return to;
    }

    /** What one build did: its result, the productive steps that ran, and the moved-key warnings. */
    private record Observed(WorkspaceResult result, Map<String, String> ran, List<String> moved, List<String> errors) {}

    private static Observed build(Path ws, Path cache, BuildKnobs knobs) throws Exception {
        Map<String, String> ran = new TreeMap<>();
        List<String> moved = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        WorkspaceBuildListener listener = new WorkspaceBuildListener() {
            @Override
            public BuildPlanListener onModuleStart(ModulePlan module) {
                String name = module.dir().getFileName().toString();
                Map<String, String> labels = new TreeMap<>();
                return new BuildPlanListener() {
                    @Override
                    public void label(String step, String label) {
                        synchronized (labels) {
                            labels.put(step, label);
                        }
                    }

                    @Override
                    public void error(String step, String code, String message) {
                        synchronized (errors) {
                            errors.add(name + " " + step + " " + code + ": " + message);
                        }
                    }

                    @Override
                    public void warn(String step, String code, String message) {
                        if (!code.equals(InputGuard.MOVED)) return;
                        synchronized (moved) {
                            moved.add(name + " " + step + ": " + message);
                        }
                    }

                    @Override
                    public void stepFinish(
                            String step,
                            @Nullable String group,
                            TaskStatus status,
                            Duration duration,
                            Duration waited) {
                        if (status != TaskStatus.SUCCESS || !BuildService.isProductiveStep(step)) return;
                        String said;
                        synchronized (labels) {
                            said = labels.getOrDefault(step, "");
                        }
                        synchronized (ran) {
                            ran.put(name + " " + step, said);
                        }
                    }
                };
            }

            @Override
            public void onModuleWarning(Path dir, String module, String step, String code, String message) {
                if (!code.equals(KeyAudit.MOVED)) return;
                synchronized (moved) {
                    moved.add(module + " " + step + ": " + message);
                }
            }
        };
        // Under a request holding what its suites settle, as the engine's job runs every build.
        WorkspaceResult result = Request.detached()
                .with(SettledReads.SLOT, new SettledReads())
                .call(() -> SessionContext.where(
                        session(cache, knobs).withKeyAudit(KeyAuditMode.STRICT),
                        () -> WorkspaceExecute.buildWorkspace(knobs.request(ws, cache), listener)));
        return new Observed(result, ran, moved, errors);
    }

    /**
     * The session a client sends beside a request under {@code knobs}: its variant and env, and the
     * {@code GRAALVM_HOME} the caller's session carries (a native-image fixture names the GraalVM it
     * found).
     */
    private static Session session(Path cache, BuildKnobs knobs) {
        return Session.defaults()
                .withCacheDir(cache)
                .withVariant(knobs.variant(), knobs.clientEnv())
                .withToolchainSpecs(null, null, SessionContext.current().graalHome(), null);
    }

    /** Each dirty module with the steps that are not cached and why. */
    private static List<String> dirty(List<TaskForecast.Module> modules) {
        return modules.stream()
                .filter(TaskForecast.Module::dirty)
                .map(m -> m.dir().getFileName() + " " + (m.reason() == null ? "" : m.reason() + " ")
                        + m.steps().stream()
                                .filter(s -> !s.cached())
                                .map(s -> s.name() + " (" + s.text() + ")")
                                .toList())
                .toList();
    }
}
