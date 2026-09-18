// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.run.CliSessionTranscript;
import cc.jumpkick.cli.run.JsonlShape;
import cc.jumpkick.cli.tui.GlobalCancel;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ExecPlan;
import cc.jumpkick.wire.protocol.PluginCommandReport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk watch run} / {@code jk dev}: build, start the app from classes, recompile on change.
 * Boot DevTools hot-restart when present; otherwise process restart. Android device deploy path
 * when the engine returns a deploy command.
 *
 * <p>On a terminal the app owns stdout and stderr and every sidecar line is prefixed with its
 * name. Under {@code --output json} the app is piped too, so stdout stays one JSONL stream: its
 * lines become {@code app-output} events beside the sidecars' {@code sidecar-output}, and its
 * starts and exits {@code app-started} / {@code app-exited}. {@code dev-ready} says the whole stack
 * is up — every sidecar's probe and, when {@code [dev] ready} or {@code ready-pattern} names one,
 * the app's own ({@link ReadyProbe}, run after every start and restart) — and says it again after
 * a process restart of the app when the app is the front door. A pattern probe on a terminal pipes
 * the app through jk, since a probe cannot read a terminal the app owns.
 *
 * <p>The session keeps one {@link CliSessionTranscript} for its whole life: the loop's builds — each
 * an engine job with its own {@code job} line — the sidecar and app events, and the finish — on
 * Ctrl-C too — land in a single {@code details.jsonl} whatever the output mode, the app's own lines
 * excepted on a terminal, where the app owns stdout.
 */
@RequiredArgsConstructor
public final class AppWatchLoop {

    /** Recompiles the watched project; the caller decides whether that is one plan or the workspace. */
    @FunctionalInterface
    public interface Compiler {
        boolean compile(Path projectDir, Path cache) throws IOException, InterruptedException;
    }

    private final GlobalOptions global;
    private final @Nullable Path jdksDir;
    private final String logPrefix;
    private final boolean noSidecars;
    private final Compiler compiler;
    /** The full rebuild a manifest or resource change triggers; same shape, different plan. */
    private final Compiler builder;

    /** Where every event goes: the session transcript always, stdout under {@code --output json}. */
    private final Consumer<String> events = line -> JsonlShape.emitEvent(line, json());

    public int run(Path projectDir, Path cache, List<String> appArgs) throws IOException, InterruptedException {
        CliSessionTranscript session = CliSessionTranscript.openAcrossJobs(projectDir, "dev", devArgv(appArgs));
        if (session != null) session.announceIf(global.verbose);
        // Ctrl-C stops the children from the signal handler, and the loop on this thread sees them
        // die — an app gone before its probe passed, a poll that returns nothing — and returns a
        // failure of its own. Two threads then finish the one transcript, and the first writer
        // wins; so the code this thread records is the interrupt's whenever one has landed, the
        // same rule the entry point applies to the process exit.
        int code;
        try {
            code = session(projectDir, cache, appArgs, session);
        } catch (IOException | InterruptedException | RuntimeException e) {
            CliSessionTranscript.finish(session, GlobalCancel.exitCodeFor(Exit.SOFTWARE), false);
            throw e;
        }
        return CliSessionTranscript.finish(session, GlobalCancel.exitCodeFor(code), global.verbose);
    }

    /** What {@code session-start} records: the verb as {@code jk dev} spells it, its options, the app's arguments. */
    private List<String> devArgv(List<String> appArgs) {
        List<String> argv = new ArrayList<>();
        argv.add("dev");
        if (noSidecars) argv.add("--no-sidecars");
        if (json()) {
            argv.add("--output");
            argv.add("json");
        }
        if (!appArgs.isEmpty()) {
            argv.add("--");
            argv.addAll(appArgs);
        }
        return argv;
    }

    private int session(Path projectDir, Path cache, List<String> appArgs, @Nullable CliSessionTranscript session)
            throws IOException, InterruptedException {
        if (!build(projectDir, cache)) return 1;

        ExecPlan plan = devPlan(projectDir, cache);
        if (plan.error() != null) {
            CliOutput.err(logPrefix + ": " + plan.error());
            return Exit.SOFTWARE;
        }
        if (!plan.deployCommand().isEmpty()) {
            return deviceLoop(projectDir, cache, plan, appArgs);
        }

        boolean devtools = plan.hotReload();
        if (plan.devtoolsInjected()) {
            CliOutput.err(logPrefix + ": spring-boot-devtools auto-injected for this session"
                    + " — add it to [dev-dependencies] to make it permanent.");
        }
        List<Path> watchRoots = rootsFromPlan(plan);
        logWatching(projectDir, watchRoots, devtools ? "DevTools hot-restart" : "process restart on change");

        // Sidecars start once and outlive every app restart below; they go down with the session.
        List<ExecPlan.Sidecar> sidecarSpecs = plan.sidecars();
        Sidecars.Listener recorder = SidecarOutput.jsonl(events, Clock.SYSTEM);
        Sidecars.Listener listener =
                json() ? recorder : SidecarOutput.both(SidecarOutput.terminal(CliOutput::err), recorder);
        Sidecars sidecars =
                Sidecars.start(noSidecars ? List.of() : sidecarSpecs, listener, Clock.SYSTEM, Sidecars.Sleeper.REAL);
        App app;
        try {
            app = startApp(plan, appArgs);
        } catch (IOException | RuntimeException e) {
            sidecars.close();
            throw e;
        }
        // Ctrl-C halts this process without unwinding, so the children are stopped from the
        // signal handler, and the transcript is finished there or never; the finally below does
        // the same on every other way out.
        AtomicReference<Process> running = new AtomicReference<>(app.process());
        try (GlobalCancel.Registration onInterrupt = GlobalCancel.onInterrupt(() -> {
                    sidecars.stopAlongside(List.of(running.get()));
                    CliSessionTranscript.finish(session, Exit.INTERRUPTED, false);
                });
                SourceWatch watch = SourceWatch.open(projectDir, watchRoots)) {
            if (!sidecars.isEmpty()) {
                Optional<String> notReady = sidecars.awaitReady();
                if (notReady.isPresent()) {
                    CliOutput.err(logPrefix + ": " + notReady.get());
                    return Exit.SOFTWARE;
                }
            }
            if (!appReady(app)) return Exit.SOFTWARE;
            ready(sidecars, plan);
            while (true) {
                Optional<SourceWatch.Changes> maybe = watch.pollChange(500, TimeUnit.MILLISECONDS);
                if (maybe.isEmpty()) {
                    if (!app.process().isAlive()) {
                        int exit = app.process().exitValue();
                        appExited(app.process());
                        CliOutput.err(logPrefix + ": app exited with code " + exit + " — stopping.");
                        return exit;
                    }
                    continue;
                }
                SourceWatch.Changes changes = maybe.get();
                if (changes.manifest()) {
                    CliOutput.err(logPrefix + ": jk.toml changed — full rebuild + restart");
                    if (build(projectDir, cache)) {
                        plan = devPlan(projectDir, cache);
                        if (plan.error() != null) {
                            CliOutput.err(logPrefix + ": " + plan.error());
                            return Exit.SOFTWARE;
                        }
                        devtools = plan.hotReload();
                        if (!noSidecars && !plan.sidecars().equals(sidecarSpecs)) {
                            CliOutput.err(logPrefix + ": sidecars changed — restart jk dev to apply");
                        }
                        app = restartApp(app, plan, appArgs);
                        running.set(app.process());
                        if (!appReady(app)) return Exit.SOFTWARE;
                        if (sidecars.frontDoor().isEmpty()) ready(sidecars, plan);
                    }
                    continue;
                }
                boolean ok = changes.resources() ? build(projectDir, cache) : compile(projectDir, cache);
                if (!ok) {
                    CliOutput.err(logPrefix + ": build failed — app keeps running the last good code.");
                    continue;
                }
                if (devtools) {
                    CliOutput.err(logPrefix + ": recompiled — DevTools restarts the context.");
                } else {
                    app = restartApp(app, plan, appArgs);
                    running.set(app.process());
                    if (!appReady(app)) return Exit.SOFTWARE;
                    if (sidecars.frontDoor().isEmpty()) ready(sidecars, plan);
                }
            }
        } finally {
            sidecars.stopAlongside(List.of(app.process()));
        }
    }

    /**
     * Wait for the app's own probe, when {@code [dev]} declares one. A probe that fails — the JVM
     * exited first, or the timeout passed — ends the session the way a sidecar's does, with the
     * same sentence shape: the whole stack was asked for and did not come up.
     */
    private boolean appReady(App app) throws InterruptedException {
        String failure = app.probe().await();
        if (failure == null) return true;
        CliOutput.err(logPrefix + ": " + failure);
        return false;
    }

    /**
     * The one line that says the stack is up, and its {@code dev-ready} event under {@code --output
     * json}: once every sidecar's probe and the app's own have passed, and again after each process
     * restart of the app when the app itself is the front door. A sidecar outlives the restart, so
     * its address stays true; the app's process is new, and the terminal would otherwise keep
     * pointing at the old one. The address shown is the front-door sidecar's, else the app's own
     * {@code [dev] ready} URL — the one place a reader learns where the app answers.
     */
    private void ready(Sidecars sidecars, ExecPlan plan) {
        String url = sidecars.frontDoor().orElse(plan.appReady().ready());
        CliOutput.err(logPrefix + ": " + SidecarOutput.readyLine(url, plan.display()));
        events.accept(SidecarOutput.devReady(Clock.SYSTEM, url, plan.display()));
    }

    private int deviceLoop(Path projectDir, Path cache, ExecPlan plan, List<String> appArgs)
            throws IOException, InterruptedException {
        List<Path> watchRoots = rootsFromPlan(plan);
        logWatching(projectDir, watchRoots, "redeploy to device on change");
        int deployed = deploy(projectDir, cache, plan.deployCommand(), appArgs);
        if (deployed != 0) return deployed;

        try (SourceWatch watch = SourceWatch.open(projectDir, watchRoots)) {
            while (true) {
                SourceWatch.Changes changes = watch.awaitChange();
                if (changes.manifest()) {
                    CliOutput.err(logPrefix + ": jk.toml changed — full rebuild + redeploy");
                }
                if (!build(projectDir, cache)) {
                    CliOutput.err(logPrefix + ": build failed — the device keeps the last good install.");
                    continue;
                }
                int exit = deploy(projectDir, cache, plan.deployCommand(), appArgs);
                if (exit != 0) {
                    CliOutput.err(logPrefix + ": deploy failed (exit " + exit + ") — will retry on change.");
                }
            }
        }
    }

    private void logWatching(Path projectDir, List<Path> watchRoots, String mode) {
        CliOutput.err(logPrefix + ": watching "
                + watchRoots.stream()
                        .map(r -> {
                            try {
                                return projectDir.relativize(r).toString();
                            } catch (IllegalArgumentException e) {
                                return r.toString();
                            }
                        })
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("src")
                + " — " + mode + ". Ctrl-C stops.");
    }

    private static List<Path> rootsFromPlan(ExecPlan plan) {
        List<Path> watchRoots = new ArrayList<>();
        for (String root : plan.watchRoots()) watchRoots.add(Path.of(root));
        return watchRoots;
    }

    private int deploy(Path projectDir, Path cache, String command, List<String> appArgs) {
        PluginCommandReport report;
        try {
            report = EngineClient.pluginCommand(EnginePaths.current(), projectDir, cache, command, appArgs);
        } catch (Exception e) {
            CliOutput.err(logPrefix + ": " + e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!report.found()) {
            CliOutput.err(logPrefix + ": the packaging plugin declares deploy command `" + command
                    + "` but does not register it");
            return Exit.SOFTWARE;
        }
        if (report.error() != null) {
            CliOutput.err(logPrefix + ": " + report.error());
            return 1;
        }
        for (String line : report.output()) CliOutput.out(line);
        return report.exit();
    }

    private boolean build(Path projectDir, Path cache) throws IOException, InterruptedException {
        return builder.compile(projectDir, cache);
    }

    private boolean compile(Path projectDir, Path cache) throws IOException, InterruptedException {
        return compiler.compile(projectDir, cache);
    }

    private ExecPlan devPlan(Path projectDir, Path cache) throws IOException {
        return EngineClient.execPlan(EnginePaths.current(), projectDir, cache, "dev", null, null);
    }

    private boolean json() {
        return global.outputIsJson();
    }

    /** The running app and the probe that says when it is listening; a new pair per start. */
    private record App(Process process, ReadyProbe probe) {}

    private App startApp(ExecPlan plan, List<String> appArgs) throws IOException {
        List<String> command = new ArrayList<>(plan.argv());
        command.addAll(appArgs);
        ProcessBuilder pb =
                new ProcessBuilder(command).directory(Path.of(plan.workingDir()).toFile());
        // Under --output json stdout is a JSONL stream, so the app is piped and its lines ride the
        // stream as events, after its app-started; stdin is still the user's. A pattern probe pipes
        // the app on a terminal too — a probe cannot read a terminal the app owns — and relays each
        // line as it arrives. Otherwise the app owns the terminal — no skipTrailingBlank: watch
        // keeps printing after the app starts, so the envelope's closing blank is still jk's to emit.
        boolean piped = json() || !plan.appReady().readyPattern().isEmpty();
        Process process =
                piped ? pb.redirectInput(ProcessBuilder.Redirect.INHERIT).start() : CliOutput.handOffTerminal(pb);
        ReadyProbe probe = new ReadyProbe("app", plan.appReady(), 0, process, Clock.SYSTEM);
        events.accept(SidecarOutput.appStarted(Clock.SYSTEM, process.pid()));
        if (piped) {
            pumpApp("stdout", process.getInputStream(), probe);
            pumpApp("stderr", process.getErrorStream(), probe);
        }
        return new App(process, probe);
    }

    /**
     * One of the app's piped streams, line by line: an {@code app-output} event on the JSONL stream,
     * or the line itself on a terminal, and either way fed to the probe after it has been shown.
     */
    private void pumpApp(String stream, InputStream in, ReadyProbe probe) {
        Thread.ofPlatform().daemon().name("app-" + stream).start(() -> {
            try (Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                OutputLines.read(reader, line -> {
                    if (json()) {
                        events.accept(SidecarOutput.appOutput(Clock.SYSTEM, stream, line));
                    } else if ("stderr".equals(stream)) {
                        CliOutput.err(line);
                    } else {
                        CliOutput.out(line);
                    }
                    probe.sawLine(line);
                });
            } catch (IOException ignored) {
                // the pipe closes with the app; its exit is reported by the loop
            }
        });
    }

    /**
     * The app's exit as an event: stdout under {@code --output json}, the transcript always. On a
     * terminal the loop's own line says it.
     */
    private void appExited(Process app) {
        events.accept(SidecarOutput.appExited(Clock.SYSTEM, app.pid(), app.exitValue()));
    }

    private App restartApp(App app, ExecPlan plan, List<String> appArgs) throws IOException, InterruptedException {
        stop(app.process());
        appExited(app.process());
        CliOutput.err(logPrefix + ": restarting app");
        return startApp(plan, appArgs);
    }

    private static void stop(Process app) {
        ProcessTrees.stop(List.of(app.toHandle()), Clock.SYSTEM);
    }

    /** Resolve cache dir from override or defaults. */
    public static Path cache(@Nullable Path override) {
        return override != null ? override : JkDirs.cache();
    }
}
