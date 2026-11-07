// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.engine.EngineProcessControl;
import cc.jumpkick.cli.engine.SilentPeer;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.DrainView;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.wire.EnginePaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code jk engine stop} — graceful drain by default: the engine refuses new jobs and exits cleanly
 * once in-flight jobs finish or its drain deadline passes. On a TTY with jobs running it blocks
 * with a live "Draining N job(s)…" region; press Ctrl-X (or pass {@code --now}) to stop now.
 * Stopping an engine that isn't running is reported, not an error (exit 0 either way).
 */
public final class EngineStopCommand implements CliCommand {

    @Override
    public String name() {
        return "stop";
    }

    @Override
    public String description() {
        return "Stop the engine (drain jobs; --now skips drain)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Stop now (abandon jobs)", "-n", "--now"),
                Opt.flag("Stop every engine of this home (not only this dir's)", "-A", "--all"),
                Opt.value(
                        "<pid>[,<pid>…]",
                        "Stop the engines with these pids, of any home (see `jk engine status`).",
                        "-p",
                        "--pid"));
    }

    @Override
    public int run(Invocation in) {
        if (in.isSet("all")) {
            EngineFleet.FleetStop stop = EngineFleet.stopAll(in.isSet("now"));
            return report(stop.results(), stop.otherHomes());
        }
        Optional<String> pidArg = in.value("pid");
        if (pidArg.isPresent()) {
            return stopByPid(pidArg.get(), in.isSet("now"));
        }
        // This engine's own generation socket: a successor may take the endpoint while it drains.
        return stop(
                EnginePaths.activeSocket(EnginePaths.current()),
                in.isSet("now"),
                EngineProbe.Patience.DEFAULT,
                SilentPeer.Grace.DEFAULT);
    }

    /**
     * Stop the engine behind {@code socket}. A graceful stop never kills an engine for silence: one
     * that accepts connections and answers nothing through {@code patience} is refused, and a
     * holder with no listener is killed only when its process shows no life under {@code grace}.
     * {@code now} is the operator's override for both.
     */
    int stop(Path socket, boolean now, EngineProbe.Patience patience, SilentPeer.Grace grace) {
        Optional<EngineProbe.Status> before = EngineProbe.statusPatiently(socket, patience);
        if (before.isEmpty()) {
            if (EngineProbe.reachable(socket)) return stopSilent(socket, now, patience);
            return stopHolder(socket, now, patience, grace);
        }
        long pid = before.get().pid();
        long started = before.get().startedAtMillis();

        // Force: stop now, then CONFIRM it went. Reporting "stopped" without checking is how a wedged
        // engine ends up being the user's problem to find and kill.
        if (now) {
            if (!EngineProcessControl.forceStop(socket)) EngineProcessControl.hardKill(pid);
            return confirmGone(pid, started);
        }

        return switch (EngineProcessControl.drainPatiently(socket, patience)) {
            case EngineProcessControl.GracefulStop.Draining(int jobs) -> draining(socket, jobs, started, pid);
            // Acknowledged idle, or the listener closed behind a status answer: exiting on its own.
            case EngineProcessControl.GracefulStop.Exiting e -> confirmGone(pid, started);
            case EngineProcessControl.GracefulStop.Unreachable u -> confirmGone(pid, started);
            case EngineProcessControl.GracefulStop.Silent s -> settle(Exit.FAILURE, silentRefusal(pid));
        };
    }

    /** The engine entered its drain and reported {@code jobs} in flight: say so, or watch it on a TTY. */
    private int draining(Path socket, int jobs, long started, long pid) {
        long deadline = EngineProbe.status(socket)
                .map(EngineProbe.Status::drainDeadlineMillis)
                .orElse(-1L);
        if (!BuildPlanConsole.isInteractiveTerminal()) {
            return settle(Exit.SUCCESS, drainingMessage(jobs, deadline));
        }
        return drainOnTty(socket, jobs, started, pid);
    }

    /**
     * What a stop that left jobs running says: how many it waits for, when the engine exits at the
     * latest, that the next job starts a successor meanwhile, and how to stop it now.
     */
    static String drainingMessage(int jobs, long deadlineMillis) {
        return "Engine is stopping after " + jobs + " running job" + (jobs == 1 ? "" : "s")
                + (deadlineMillis > 0 ? ", by " + EngineStatusCommand.wallClock(deadlineMillis) : "")
                + "; `jk engine stop -n` stops it now";
    }

    /**
     * Settle with the chip the outcome earns: green only when the engine is actually gone. The exit
     * code and the chip come from one decision, so a failure exit can never print a cheerful
     * "stopped" — the point of confirming the process is gone rather than reporting the request.
     * Failures go to stderr, where a caller that only wants the happy path can ignore them.
     */
    private static int settle(int exit, String message) {
        if (exit == Exit.SUCCESS) CommandWedge.printOk("Engine", message);
        else CommandWedge.printFail("Engine", message);
        return exit;
    }

    /**
     * The engine accepts connections and answers nothing through every probe. On the graceful path
     * that is a refusal, never a kill: a coordinator mid-build answers seconds or minutes late, and
     * killing it abandons every other terminal's jobs. {@code --now} is the operator's override.
     */
    private int stopSilent(Path socket, boolean now, EngineProbe.Patience patience) {
        long pid = EngineProcessControl.unresponsiveHolderPid(socket);
        if (!now) return settle(Exit.FAILURE, silentRefusal(pid));
        if (!EngineProcessControl.forceStop(socket, patience.replyTimeoutMillis())) EngineProcessControl.hardKill(pid);
        if (pid <= 0 || EngineFleet.waitForExit(pid)) {
            return settle(Exit.SUCCESS, "Engine stopped now; it was not answering");
        }
        return settle(Exit.FAILURE, "Engine pid " + pid + " did NOT exit, even after a hard kill.");
    }

    /** Why a graceful stop leaves a silent engine alone, naming it, what it holds and the way out. */
    static String silentRefusal(long pid) {
        String life = pid > 0
                ? SilentPeer.Life.of(pid, Clock.SYSTEM)
                        .map(l -> " (" + l.describe() + ")")
                        .orElse("")
                : "";
        return "engine" + (pid > 0 ? " pid " + pid : "") + life
                + " did not answer the stop and was not killed; retry, or run `jk engine stop -n`";
    }

    /**
     * No listener does not mean no engine: a process can hold this directory's election state
     * while never binding its socket — and then every fresh spawn loses the election to it and
     * exits silently, so reporting "not running" leaves the user to find the JVM by hand. That
     * wedge is what stop exists to clear: kill it and confirm it went, on the graceful path too,
     * because an engine with no listener has no job to drain. One whose process shows life is
     * the exception — mid-build with its wire starved, still loading, or a pid recycled onto
     * another JVM — and only {@code --now} kills it.
     */
    private int stopHolder(Path socket, boolean now, EngineProbe.Patience patience, SilentPeer.Grace grace) {
        long pid = EngineProcessControl.unresponsiveHolderPid(socket);
        if (pid <= 0) {
            return settle(Exit.SUCCESS, "not running");
        }
        if (!now) {
            Optional<SilentPeer.Pulse> pulse = SilentPeer.Pulse.of(pid, Clock.SYSTEM, patience.backoff(), grace);
            if (pulse.isEmpty()) return settle(Exit.SUCCESS, "not running");
            if (pulse.get().alive()) {
                return settle(
                        Exit.FAILURE,
                        "engine pid " + pid + " is not answering but shows life ("
                                + pulse.get().life().describe() + "); retry, or run `jk engine stop -n`");
            }
        }
        EngineProcessControl.hardKill(pid);
        if (EngineFleet.waitForExit(pid)) {
            return settle(Exit.SUCCESS, "Engine pid " + pid + " was not answering and was killed");
        }
        return settle(Exit.FAILURE, "engine pid " + pid + " is not answering and survived a hard kill");
    }

    /**
     * Wait for the engine process to actually disappear, escalating to a hard kill if it does not.
     *
     * <p>An engine that was asked to stop and did not is the one outcome a user cannot be expected to
     * handle themselves — on Windows it means finding the right JVM in Task Manager. So the wedge reports
     * what happened rather than what was requested, and a survivor is a failure exit rather than a
     * cheerful "stopped".
     */
    private int confirmGone(long pid, long started) {
        if (EngineFleet.waitForExit(pid)) {
            CommandWedge.printLine(stoppedWedge(elapsed(started)));
            return Exit.SUCCESS;
        }
        EngineProcessControl.hardKill(pid);
        if (EngineFleet.waitForExit(pid)) {
            return settle(Exit.SUCCESS, "Engine stopped with a hard kill");
        }
        return settle(Exit.FAILURE, "Engine pid " + pid + " did NOT exit, even after a hard kill.");
    }

    /**
     * Stop engines by pid — the handle {@code jk engine status} prints — one or a comma-separated
     * list, of this home or any other.
     *
     * <p>Exists so that clearing a stray engine never means reaching for {@code kill}. On Windows that
     * would mean identifying the right JVM in Task Manager, which is not a reasonable thing to ask.
     * The list form is what {@code stop -A} and {@code status} print for the engines of other homes.
     *
     * <p>Package-private: the parse-failure branch is the only settle here a test can reach without a
     * live engine to stop.
     */
    int stopByPid(String raw, boolean now) {
        List<Long> pids = new ArrayList<>();
        for (String part : raw.split(",", -1)) {
            try {
                pids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException e) {
                return settle(Exit.FAILURE, "not a pid: " + part.trim());
            }
        }
        List<EngineFleet.StopResult> results = EngineFleet.stopByPids(pids, now);
        List<Long> missing = new ArrayList<>(pids);
        for (EngineFleet.StopResult r : results) missing.remove(r.member().pid());
        if (results.isEmpty()) return settle(Exit.FAILURE, "no running engine with pid " + joinPids(missing));
        int exit = report(results, List.of());
        if (missing.isEmpty()) return exit;
        return settle(Exit.FAILURE, "no running engine with pid " + joinPids(missing));
    }

    /** {@code 101, 202}: pids for a sentence. */
    private static String joinPids(List<Long> pids) {
        List<String> out = new ArrayList<>();
        for (long pid : pids) out.add(Long.toString(pid));
        return String.join(", ", out);
    }

    /** {@code jk engine stop -p 101,202}: the command that stops exactly these engines. */
    static String stopCommandFor(List<EngineFleet.Member> members) {
        List<String> pids = new ArrayList<>();
        for (EngineFleet.Member m : members) pids.add(Long.toString(m.pid()));
        return "jk engine stop -p " + String.join(",", pids);
    }

    /**
     * Where an engine of another home runs: its home; else {@code state <dir>} when its spawn line
     * names only its state directory (a custom {@code JK_STATE_DIR}); else {@code home unknown}.
     */
    static String homeLabel(EngineFleet.Member member) {
        Path home = member.home();
        if (home != null) return home.toString();
        Path stateDir = member.stateDir();
        return stateDir == null ? "home unknown" : "state " + stateDir;
    }

    /**
     * {@code left 2 engines of other homes running (/scratch/a: pid 101, 202); `jk engine stop -p
     * 101,202` stops them} — what {@code -A} did not touch, grouped by home, and the command for it.
     * Empty when every engine belonged to this home.
     */
    static String otherHomesNote(List<EngineFleet.Member> others) {
        if (others.isEmpty()) return "";
        Map<String, List<Long>> byHome = new LinkedHashMap<>();
        for (EngineFleet.Member m : others) {
            byHome.computeIfAbsent(homeLabel(m), k -> new ArrayList<>()).add(m.pid());
        }
        List<String> groups = new ArrayList<>();
        byHome.forEach((home, pids) -> groups.add(home + ": pid " + joinPids(pids)));
        return "left " + others.size() + (others.size() == 1 ? " engine of another home" : " engines of other homes")
                + " running (" + String.join("; ", groups) + "); `" + stopCommandFor(others) + "` stops "
                + (others.size() == 1 ? "it" : "them");
    }

    /**
     * Report what actually happened to each engine, rather than assuming a request was obeyed.
     *
     * <p>A killed engine is called out because it means the clean path did not work; a survivor, and an
     * engine left alone for the life it showed without a reply, are called out loudly because those are
     * the cases a user may still have to act on — the whole point being that they should never have to
     * guess.
     *
     * <p>Package-private: a synthetic {@link EngineFleet.StopResult} is the only way to exercise the
     * survivor settle without a wedged engine on the machine running the test.
     */
    int report(List<EngineFleet.StopResult> results, List<EngineFleet.Member> otherHomes) {
        String others = otherHomesNote(otherHomes);
        if (results.isEmpty()) {
            return settle(
                    Exit.SUCCESS,
                    others.isEmpty() ? "no engines running" : "no engines of this home running; " + others);
        }
        int stopped = 0;
        int killed = 0;
        int draining = 0;
        List<Long> leftAlone = new ArrayList<>();
        List<Long> survived = new ArrayList<>();
        for (EngineFleet.StopResult r : results) {
            switch (r.outcome()) {
                case STOPPED -> stopped++;
                case KILLED -> killed++;
                case DRAINING -> draining++;
                case LEFT_ALONE -> leftAlone.add(r.member().pid());
                case SURVIVED -> survived.add(r.member().pid());
            }
        }
        StringBuilder msg = new StringBuilder();
        int gone = stopped + killed;
        msg.append(gone).append(gone == 1 ? " engine stopped" : " engines stopped");
        if (killed > 0) msg.append(" (").append(killed).append(" needed a hard kill)");
        if (draining > 0) {
            msg.append(", ").append(draining).append(" finishing running jobs (`jk engine stop -n` stops now)");
        }
        if (!leftAlone.isEmpty()) {
            msg.append(", ")
                    .append(leftAlone.size())
                    .append(" not answering, left alone (`jk engine stop -n` stops it): pid ")
                    .append(leftAlone);
        }
        if (!survived.isEmpty()) {
            msg.append(", ")
                    .append(survived.size())
                    .append(" did NOT exit: pid ")
                    .append(survived);
        }
        if (!others.isEmpty()) msg.append("; ").append(others);
        return settle(survived.isEmpty() && leftAlone.isEmpty() ? Exit.SUCCESS : Exit.FAILURE, msg.toString());
    }

    /**
     * Block on a TTY with the live drain region until the engine exits or Ctrl-X forces it. The
     * draining engine answers status on its own generation socket until it exits, so the job count
     * comes from there; exit is the process going, never the socket.
     */
    private int drainOnTty(Path socket, int jobs, long started, long pid) {
        DrainView view = DrainView.start(jobs, GlobalConfig.nerdFont());
        try {
            while (true) {
                if (view.forceRequested()) {
                    EngineProcessControl.forceStop(socket);
                    break;
                }
                if (pid <= 0
                        || !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) break;
                EngineProbe.status(socket)
                        .filter(s -> s.pid() == pid)
                        .ifPresent(s -> view.setJobs(Math.max(0, s.activeBuildPlans())));
                sleep(200);
            }
            view.settleStopped(stoppedWedge(elapsed(started)));
        } finally {
            view.close();
        }
        return Exit.SUCCESS;
    }

    private static long elapsed(long startedAtMillis) {
        return Math.max(0, System.currentTimeMillis() - startedAtMillis);
    }

    private static String stoppedWedge(long ranMs) {
        return JkWedge.chipLine(
                Glyphs.STOP, "Engine", GlobalConfig.nerdFont(), "Engine stopped. Ran for " + uptime(ranMs) + ".");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Human uptime {@code 14d 3h 37m 13s}, dropping leading zero units but always ending in seconds. */
    static String uptime(long millis) {
        long s = millis / 1000;
        long days = s / 86_400;
        long hours = (s % 86_400) / 3_600;
        long mins = (s % 3_600) / 60;
        long secs = s % 60;
        StringBuilder b = new StringBuilder();
        if (days > 0) b.append(days).append("d ");
        if (days > 0 || hours > 0) b.append(hours).append("h ");
        if (days > 0 || hours > 0 || mins > 0) b.append(mins).append("m ");
        return b.append(secs).append("s").toString();
    }
}
