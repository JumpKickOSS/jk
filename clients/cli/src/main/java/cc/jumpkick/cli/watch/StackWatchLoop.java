// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.ClientUpdate;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.GlobalCancel;
import cc.jumpkick.config.EnvLookup;
import cc.jumpkick.host.ChildEnv;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.IOException;
import java.io.InputStream;
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
    private final boolean colour = Theme.colorEnabled();

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

    /**
     * What a stack runs, by name: its runnable members' directories and every sidecar under its
     * supervisor. A session starts one and keeps it; a {@code jk.toml} edit can only report a new one.
     */
    record Shape(Map<String, String> members, Map<String, ExecPlan.Sidecar> sidecars) {

        /**
         * The shape of {@code stack} running {@code wanted}, whose JVM members plan {@code memberPlans}:
         * the stack's sidecars (a node member's only when it is wanted, none of the others under
         * {@code noSidecars}), then each JVM member's own not already named. A plan that is not a
         * stack, a root left with one runnable member, has no members.
         */
        static Shape of(ExecPlan stack, Set<String> wanted, List<ExecPlan> memberPlans, boolean noSidecars) {
            if (!ExecPlan.DEV_STACK.equals(stack.kind())) return new Shape(Map.of(), Map.of());
            Map<String, String> members = stack.members();
            Map<String, ExecPlan.Sidecar> sidecars = new LinkedHashMap<>();
            for (ExecPlan.Sidecar s : stack.sidecars()) {
                boolean member = members.containsKey(s.name());
                if (member ? wanted.contains(s.name()) : !noSidecars) sidecars.put(s.name(), s);
            }
            if (!noSidecars) {
                for (ExecPlan plan : memberPlans) {
                    for (ExecPlan.Sidecar s : plan.sidecars()) sidecars.putIfAbsent(s.name(), s);
                }
            }
            return new Shape(members, sidecars);
        }
    }

    /** The members the session runs; set once they are planned. */
    private Set<String> wanted = Set.of();

    /** The JVM members the session runs; set once they are planned. */
    private List<Member> apps = List.of();

    /** The shape the session started; it runs that one until it ends. */
    private @Nullable Shape running;

    /** The last shape a re-plan reported as not running, so one edit says so once, not once per member. */
    private @Nullable Shape reported;

    int run(Path cache, List<String> appArgs) throws IOException, InterruptedException {
        Map<String, String> members = stack.members();
        for (String name : selected) {
            if (!members.containsKey(name)) {
                CliOutput.err(loop.logPrefix() + ": no runnable member `" + name + "`; the workspace runs "
                        + String.join(", ", members.keySet()));
                return Exit.USAGE;
            }
        }
        Set<String> wanted = new LinkedHashSet<>(selected.isEmpty() ? members.keySet() : selected);
        Set<String> nodeMembers = new LinkedHashSet<>();
        for (ExecPlan.Sidecar s : stack.sidecars()) {
            if (members.containsKey(s.name())) nodeMembers.add(s.name());
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
                CliOutput.err(loop.logPrefix() + ": " + name + " deploys to a device; run jk dev in " + dir);
                return Exit.USAGE;
            }
            apps.add(new Member(name, dir, plan));
        }
        this.wanted = wanted;
        this.apps = apps;
        running = shape(stack);
        Map<String, ExecPlan.Sidecar> sidecars = running.sidecars();

        loop.attachKeys();
        Map<String, String> urls = new LinkedHashMap<>(DevUrls.of(List.copyOf(sidecars.values())));
        for (Member m : apps) urls.put(m.name, m.plan.appReady().ready());
        Map<String, String> exports = DevUrls.exports(urls);
        Sidecars supervisor = Sidecars.start(
                DevUrls.withExports(List.copyOf(sidecars.values()), exports),
                loop.sidecarListener(),
                Clock.SYSTEM,
                Sidecars.Sleeper.REAL);
        try (GlobalCancel.Registration onInterrupt = GlobalCancel.onInterrupt(() -> {
            supervisor.close();
            loop.interrupted();
        })) {
            Optional<String> notBefore = supervisor.awaitRunBefore();
            if (notBefore.isPresent()) {
                CliOutput.err(loop.logPrefix() + ": " + notBefore.get());
                return Exit.SOFTWARE;
            }
            for (Member m : apps) {
                start(supervisor, m, appArgs, exports);
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
            loop.hintKeys();
            return watch(supervisor, cache, apps, appArgs, exports);
        } finally {
            for (Member m : apps) {
                if (m.watch != null) m.watch.close();
            }
            supervisor.close();
        }
    }

    /**
     * Poll every member's sources in turn; restart the one that changed, end when one exits. A key
     * is read between polls: {@code r} rebuilds and restarts every member, {@code q} ends the
     * session. A stack of node members alone has nothing to poll: it waits on the supervisor until
     * every process is gone for good, or Ctrl-C stops them.
     */
    private int watch(
            Sidecars supervisor, Path cache, List<Member> apps, List<String> appArgs, Map<String, String> exports)
            throws IOException, InterruptedException {
        if (apps.isEmpty()) {
            supervisor.awaitAllExited();
            CliOutput.err(loop.logPrefix() + ": every process exited; stopping");
            return Exit.SOFTWARE;
        }
        long slice = Math.max(50, 500 / Math.max(1, apps.size()));
        while (true) {
            for (Member m : apps) {
                Process process = m.run().process();
                if (!process.isAlive()) {
                    int exit = process.exitValue();
                    exited(m);
                    CliOutput.err(loop.logPrefix() + ": " + m.name + " exited with code " + exit + "; stopping");
                    return exit;
                }
                Optional<SourceWatch.Changes> maybe = m.watch().pollChange(slice, TimeUnit.MILLISECONDS);
                DevKeys.Action key = loop.key();
                if (key == DevKeys.Action.QUIT) return loop.quit();
                if (key == DevKeys.Action.RESTART) {
                    CliOutput.err(loop.logPrefix() + ": rebuilding and restarting " + String.join(", ", names(apps)));
                    for (Member each : apps) {
                        Integer exit = change(supervisor, cache, each, REBUILD, appArgs, exports);
                        if (exit != null) return exit;
                    }
                    continue;
                }
                if (maybe.isEmpty()) continue;
                if (ClientUpdate.updatedBetweenPasses()) return Exit.SUCCESS;
                Integer exit = change(supervisor, cache, m, maybe.get(), appArgs, exports);
                if (exit != null) return exit;
            }
        }
    }

    /** What {@code r} asks of every member: the pass a {@code jk.toml} edit runs. */
    private static final SourceWatch.Changes REBUILD = new SourceWatch.Changes(false, false, true);

    /**
     * Run the pass {@code changes} asks of {@code m}, then restart it unless DevTools restarts its
     * context. The code the session ends with, or null when it runs on.
     */
    private @Nullable Integer change(
            Sidecars supervisor,
            Path cache,
            Member m,
            SourceWatch.Changes changes,
            List<String> appArgs,
            Map<String, String> exports)
            throws IOException, InterruptedException {
        AppWatchLoop.Pass pass = AppWatchLoop.Pass.of(changes);
        if (!loop.pass(pass, m.dir, cache)) {
            CliOutput.err(loop.logPrefix() + ": " + m.name + ": build failed; it keeps running the last good code");
            return null;
        }
        if (changes.manifest()) {
            ExecPlan plan = loop.devPlan(m.dir, cache);
            if (plan.error() != null) {
                CliOutput.err(loop.logPrefix() + ": " + m.name + ": " + plan.error());
                return Exit.SOFTWARE;
            }
            m.plan = plan;
            replanned(loop.devPlan(Path.of(stack.workingDir()), cache));
        }
        if (m.plan.hotReload() && !changes.manifest()) {
            CliOutput.err(loop.logPrefix() + ": " + m.name + ": " + pass.done + "; DevTools restarts the context");
            return null;
        }
        ProcessTrees.stop(List.of(m.run().process().toHandle()), Clock.SYSTEM);
        exited(m);
        CliOutput.err(loop.logPrefix() + ": restarting " + m.name);
        start(supervisor, m, appArgs, exports);
        String failure = m.run().probe().await();
        if (failure != null) {
            CliOutput.err(loop.logPrefix() + ": " + m.name + ": " + failure);
            return Exit.SOFTWARE;
        }
        return null;
    }

    /**
     * Say when {@code replanned}, the stack planned again after a {@code jk.toml} edit, no longer runs
     * the members and sidecars the session does: those start once, so the change waits for the next
     * session, as it does for {@code jk dev} in one app.
     */
    private void replanned(ExecPlan replanned) {
        if (replanned.error() != null) {
            CliOutput.err(loop.logPrefix() + ": " + replanned.error());
            return;
        }
        Shape now = shape(replanned);
        if (now.equals(running)) {
            reported = null;
        } else if (!now.equals(reported)) {
            reported = now;
            CliOutput.err(loop.logPrefix() + ": the workspace's members or sidecars changed; restart jk dev to apply");
        }
    }

    private Shape shape(ExecPlan plan) {
        List<ExecPlan> plans = new ArrayList<>();
        for (Member m : apps) plans.add(m.plan);
        return Shape.of(plan, wanted, plans, loop.noSidecars());
    }

    private static List<String> names(List<Member> apps) {
        List<String> out = new ArrayList<>();
        for (Member m : apps) out.add(m.name);
        return out;
    }

    private void start(Sidecars supervisor, Member m, List<String> appArgs, Map<String, String> exports)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(m.plan.argv());
        command.addAll(appArgs);
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(Path.of(m.plan.workingDir()).toFile());
        EnvLookup.layerUnder(pb.environment(), m.plan.appDotEnv());
        ChildEnv.putAll(pb, m.plan.appEnv());
        ChildEnv.putAll(pb, DevUrls.forApp(m.name, exports));
        Process process = supervisor.startCompanion(pb::start);
        ReadyProbe probe = new ReadyProbe(m.name, m.plan.appReady(), 0, process, Clock.SYSTEM);
        m.run = new Run(process, probe);
        loop.emit(SidecarOutput.appStarted(Clock.SYSTEM, m.name, process.pid()));
        pump(m.name, probe, "stdout", process.getInputStream());
        pump(m.name, probe, "stderr", process.getErrorStream());
    }

    /** One of a member's streams: an {@code app-output} event, and on a terminal the line under its prefix. */
    private void pump(String name, ReadyProbe probe, String stream, InputStream in) {
        OutputLines.pump(name + "-" + stream, in, line -> {
            loop.emit(SidecarOutput.appOutput(Clock.SYSTEM, name, stream, line));
            if (!loop.json()) CliOutput.err(SidecarOutput.prefix(name, colour) + line);
            probe.sawLine(line);
        });
    }

    private void exited(Member m) {
        Process process = m.run().process();
        loop.emit(SidecarOutput.appExited(Clock.SYSTEM, m.name, process.pid(), ProcessTrees.exitCode(process)));
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

    private static List<Path> roots(ExecPlan plan) {
        List<Path> out = new ArrayList<>();
        for (String root : plan.watchRoots()) out.add(Path.of(root));
        return out;
    }
}
