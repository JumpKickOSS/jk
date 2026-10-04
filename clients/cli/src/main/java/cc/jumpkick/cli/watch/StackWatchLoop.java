// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.tui.GlobalCancel;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk dev} at a workspace root: every runnable member at once. Each JVM member runs from its
 * own dev plan and restarts on its own sources; each node member's dev server, and every sidecar a
 * member's plan or the root declares, runs once under one {@link Sidecars} supervisor. Every line
 * is prefixed with the name of the process that wrote it; one {@code ready ·} line lists every
 * front door; one Ctrl-C stops every tree. {@code -m} narrows the members.
 */
final class StackWatchLoop {

    private final AppWatchLoop loop;
    private final ExecPlan stack;
    private final List<String> selected;
    private final boolean colour = GlobalConfig.colorEnabled();

    StackWatchLoop(AppWatchLoop loop, ExecPlan stack, List<String> selected) {
        this.loop = loop;
        this.stack = stack;
        this.selected = selected;
    }

    /** One JVM member: where it lives, its plan, its running process, the probe of that process. */
    private static final class Member {
        final String name;
        final Path dir;
        ExecPlan plan;

        @Nullable
        Run run;

        @Nullable
        SourceWatch watch;

        Member(String name, Path dir, ExecPlan plan) {
            this.name = name;
            this.dir = dir;
            this.plan = plan;
        }

        Run run() {
            return Objects.requireNonNull(run, name);
        }

        SourceWatch watch() {
            return Objects.requireNonNull(watch, name);
        }
    }

    /** A started member: its process and the probe of that process. */
    private record Run(Process process, ReadyProbe probe) {}

    int run(Path cache, List<String> appArgs) throws IOException, InterruptedException {
        Map<String, String> members = stack.members();
        for (String name : selected) {
            if (!members.containsKey(name)) {
                CliOutput.err(loop.logPrefix() + ": no runnable member `" + name + "` — the workspace runs "
                        + String.join(", ", members.keySet()));
                return Exit.USAGE;
            }
        }
        Set<String> wanted = new LinkedHashSet<>(selected.isEmpty() ? members.keySet() : selected);
        Map<String, ExecPlan.Sidecar> sidecars = new LinkedHashMap<>();
        Set<String> nodeMembers = new LinkedHashSet<>();
        for (ExecPlan.Sidecar s : stack.sidecars()) {
            boolean member = members.containsKey(s.name());
            if (member) nodeMembers.add(s.name());
            if (member ? wanted.contains(s.name()) : !loop.noSidecars()) sidecars.put(s.name(), s);
        }
        List<Member> apps = new ArrayList<>();
        for (String name : wanted) {
            if (nodeMembers.contains(name)) continue;
            Path dir = Path.of(members.get(name));
            ExecPlan plan = loop.devPlan(dir, cache);
            if (plan.error() != null) {
                CliOutput.err(loop.logPrefix() + ": " + name + ": " + plan.error());
                return Exit.SOFTWARE;
            }
            if (!plan.deployCommand().isEmpty()) {
                CliOutput.err(loop.logPrefix() + ": " + name + " deploys to a device and does not run in a stack —"
                        + " run jk dev in " + dir);
                return Exit.USAGE;
            }
            if (!loop.noSidecars()) for (ExecPlan.Sidecar s : plan.sidecars()) sidecars.putIfAbsent(s.name(), s);
            apps.add(new Member(name, dir, plan));
        }

        Map<String, String> urls = new LinkedHashMap<>(DevUrls.of(List.copyOf(sidecars.values())));
        for (Member m : apps) urls.put(m.name, m.plan.appReady().ready());
        Map<String, String> exports = DevUrls.exports(urls);
        Sidecars supervisor = Sidecars.start(
                DevUrls.withExports(List.copyOf(sidecars.values()), exports),
                loop.sidecarListener(),
                Clock.SYSTEM,
                Sidecars.Sleeper.REAL);
        try (GlobalCancel.Registration onInterrupt = GlobalCancel.onInterrupt(() -> {
            supervisor.stopAlongside(running(apps));
            loop.interrupted();
        })) {
            for (Member m : apps) {
                start(m, appArgs, exports);
                m.watch = SourceWatch.open(m.dir, roots(m.plan));
            }
            CliOutput.err(loop.logPrefix() + ": running " + String.join(", ", wanted));
            Optional<String> notReady = supervisor.awaitReady();
            if (notReady.isPresent()) {
                CliOutput.err(loop.logPrefix() + ": " + notReady.get());
                return Exit.SOFTWARE;
            }
            for (Member m : apps) {
                String failure = m.run().probe().await();
                if (failure != null) {
                    CliOutput.err(loop.logPrefix() + ": " + m.name + ": " + failure);
                    return Exit.SOFTWARE;
                }
            }
            ready(supervisor, apps, wanted);
            return watch(cache, apps, appArgs, exports);
        } finally {
            for (Member m : apps) {
                if (m.watch != null) m.watch.close();
            }
            supervisor.stopAlongside(running(apps));
        }
    }

    /** Poll every member's sources in turn; restart the one that changed, end when one exits. */
    private int watch(Path cache, List<Member> apps, List<String> appArgs, Map<String, String> exports)
            throws IOException, InterruptedException {
        long slice = Math.max(50, 500 / Math.max(1, apps.size()));
        while (true) {
            for (Member m : apps) {
                Process process = m.run().process();
                if (!process.isAlive()) {
                    int exit = process.exitValue();
                    exited(m);
                    CliOutput.err(loop.logPrefix() + ": " + m.name + " exited with code " + exit + " — stopping.");
                    return exit;
                }
                Optional<SourceWatch.Changes> maybe = m.watch().pollChange(slice, TimeUnit.MILLISECONDS);
                if (maybe.isEmpty()) continue;
                SourceWatch.Changes changes = maybe.get();
                boolean ok = changes.manifest() || changes.resources()
                        ? loop.build(m.dir, cache)
                        : loop.compile(m.dir, cache);
                if (!ok) {
                    CliOutput.err(
                            loop.logPrefix() + ": " + m.name + ": build failed — it keeps running the last good code.");
                    continue;
                }
                if (changes.manifest()) {
                    ExecPlan plan = loop.devPlan(m.dir, cache);
                    if (plan.error() != null) {
                        CliOutput.err(loop.logPrefix() + ": " + m.name + ": " + plan.error());
                        return Exit.SOFTWARE;
                    }
                    m.plan = plan;
                }
                if (m.plan.hotReload() && !changes.manifest()) {
                    CliOutput.err(loop.logPrefix() + ": " + m.name + ": recompiled — DevTools restarts the context.");
                    continue;
                }
                ProcessTrees.stop(List.of(process.toHandle()), Clock.SYSTEM);
                exited(m);
                CliOutput.err(loop.logPrefix() + ": restarting " + m.name);
                start(m, appArgs, exports);
                String failure = m.run().probe().await();
                if (failure != null) {
                    CliOutput.err(loop.logPrefix() + ": " + m.name + ": " + failure);
                    return Exit.SOFTWARE;
                }
            }
        }
    }

    private void start(Member m, List<String> appArgs, Map<String, String> exports) throws IOException {
        List<String> command = new ArrayList<>(m.plan.argv());
        command.addAll(appArgs);
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(Path.of(m.plan.workingDir()).toFile());
        pb.environment().putAll(m.plan.appEnv());
        pb.environment().putAll(DevUrls.forApp(m.name, exports));
        Process process = pb.start();
        ReadyProbe probe = new ReadyProbe(m.name, m.plan.appReady(), 0, process, Clock.SYSTEM);
        m.run = new Run(process, probe);
        loop.emit(SidecarOutput.appStarted(Clock.SYSTEM, m.name, process.pid()));
        pump(m.name, probe, "stdout", process.getInputStream());
        pump(m.name, probe, "stderr", process.getErrorStream());
    }

    /** One of a member's streams: an {@code app-output} event, and on a terminal the line under its prefix. */
    private void pump(String name, ReadyProbe probe, String stream, InputStream in) {
        Thread.ofPlatform().daemon().name(name + "-" + stream).start(() -> {
            try (Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                OutputLines.read(reader, line -> {
                    loop.emit(SidecarOutput.appOutput(Clock.SYSTEM, name, stream, line));
                    if (!loop.json()) CliOutput.err(SidecarOutput.prefix(name, colour) + line);
                    probe.sawLine(line);
                });
            } catch (IOException ignored) {
                // the pipe closes with the process; its exit is reported by the loop
            }
        });
    }

    private void exited(Member m) {
        Process process = m.run().process();
        loop.emit(SidecarOutput.appExited(Clock.SYSTEM, m.name, process.pid(), process.exitValue()));
    }

    /** The one line, and its {@code dev-ready} event, that says the whole stack is up. */
    private void ready(Sidecars supervisor, List<Member> apps, Set<String> wanted) {
        List<String> doors = new ArrayList<>(supervisor.frontDoors());
        if (doors.isEmpty()) {
            for (Member m : apps) {
                if (!m.plan.appReady().ready().isEmpty())
                    doors.add(m.plan.appReady().ready());
            }
        }
        String names = String.join(", ", wanted);
        CliOutput.err(loop.logPrefix() + ": " + SidecarOutput.readyLine(doors, names));
        loop.emit(SidecarOutput.devReady(Clock.SYSTEM, doors, names));
    }

    private static List<Process> running(List<Member> apps) {
        List<Process> out = new ArrayList<>();
        for (Member m : apps) {
            if (m.run != null) out.add(m.run.process());
        }
        return out;
    }

    private static List<Path> roots(ExecPlan plan) {
        List<Path> out = new ArrayList<>();
        for (String root : plan.watchRoots()) out.add(Path.of(root));
        return out;
    }
}
