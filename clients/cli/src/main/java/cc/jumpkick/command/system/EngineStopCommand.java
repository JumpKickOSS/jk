// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.engine.EngineProcessControl;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.DrainView;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.wire.EnginePaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
                Opt.flag("Stop now (abandon jobs)", "--now"),
                Opt.flag("Stop every engine (not only this dir's)", "--all"),
                Opt.value("<pid>", "Stop the engine with this pid (see `jk engine status`).", "--pid"));
    }

    @Override
    public int run(Invocation in) {
        if (in.isSet("all")) {
            return report(EngineFleet.stopAll(in.isSet("now")));
        }
        Optional<String> pidArg = in.value("pid");
        if (pidArg.isPresent()) {
            return stopByPid(pidArg.get(), in.isSet("now"));
        }
        EnginePaths.Paths paths = EnginePaths.current();
        // This engine's own generation socket: a successor may take the endpoint while it drains.
        Path socket = EnginePaths.activeSocket(paths);
        Optional<EngineProbe.Status> before = EngineProbe.status(socket);
        if (before.isEmpty()) {
            return stopUnresponsiveHolder(paths);
        }
        long started = before.get().startedAtMillis();

        // Force: stop now, then CONFIRM it went. Reporting "stopped" without checking is how a wedged
        // engine ends up being the user's problem to find and kill.
        if (in.isSet("now")) {
            if (!EngineProcessControl.forceStop(socket))
                EngineProcessControl.hardKill(before.get().pid());
            return confirmGone(before.get().pid(), started);
        }

        // Graceful drain. The engine enters draining and reports the in-flight job count.
        int jobs = EngineProcessControl.drain(socket);
        if (jobs <= 0) {
            // Idle (or already gone): the engine should exit immediately — verify, and escalate if not.
            return confirmGone(before.get().pid(), started);
        }
        long deadline = EngineProbe.status(socket)
                .map(EngineProbe.Status::drainDeadlineMillis)
                .orElse(-1L);
        if (!BuildPlanConsole.isInteractiveTerminal()) {
            return settle(Exit.SUCCESS, drainingMessage(jobs, deadline));
        }
        return drainOnTty(socket, jobs, started, before.get().pid());
    }

    /**
     * What a stop that left jobs running says: how many it waits for, when the engine exits at the
     * latest, that the next job starts a successor meanwhile, and how to stop it now.
     */
    static String drainingMessage(int jobs, long deadlineMillis) {
        return "Engine is stopping: waiting for " + jobs + " in-flight job" + (jobs == 1 ? "" : "s")
                + (deadlineMillis > 0 ? " (exits by " + EngineStatusCommand.wallClock(deadlineMillis) + ")" : "")
                + "; the next build starts a new engine. `jk engine stop --now` stops it now";
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
     * No handshake does not mean no engine: a process can hold this directory's election state
     * while never answering its socket — and then every fresh spawn loses the election to it and
     * exits silently, so reporting "not running" leaves the user to find the JVM by hand. That
     * wedge is exactly what stop exists to clear: kill it and confirm it went, on the graceful
     * path too, because an engine with no working socket has nothing to drain.
     */
    private int stopUnresponsiveHolder(EnginePaths.Paths paths) {
        long pid = EngineProcessControl.unresponsiveHolderPid(EnginePaths.activeSocket(paths));
        if (pid <= 0) {
            return settle(Exit.SUCCESS, "not running");
        }
        EngineProcessControl.hardKill(pid);
        if (EngineFleet.waitForExit(pid)) {
            return settle(
                    Exit.SUCCESS,
                    "Engine pid " + pid + " held this directory's engine state without answering its socket"
                            + " — killed.");
        }
        return settle(
                Exit.FAILURE,
                "Engine pid " + pid + " holds the engine state, does not answer its socket, and survived a"
                        + " hard kill.");
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
            return settle(Exit.SUCCESS, "Engine stopped after a hard kill (it did not exit on request).");
        }
        return settle(Exit.FAILURE, "Engine pid " + pid + " did NOT exit, even after a hard kill.");
    }

    /**
     * Stop one engine by pid — the handle {@code jk engine status} prints.
     *
     * <p>Exists so that clearing a stray engine never means reaching for {@code kill}. On Windows that
     * would mean identifying the right JVM in Task Manager, which is not a reasonable thing to ask.
     *
     * <p>Package-private: the parse-failure branch is the only settle here a test can reach without a
     * live engine to stop.
     */
    int stopByPid(String raw, boolean now) {
        long pid;
        try {
            pid = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return settle(Exit.FAILURE, "not a pid: " + raw);
        }
        Optional<EngineFleet.StopResult> result = EngineFleet.stopByPid(pid, now);
        if (result.isEmpty()) {
            return settle(Exit.FAILURE, "no running engine with pid " + pid);
        }
        return report(List.of(result.get()));
    }

    /**
     * Report what actually happened to each engine, rather than assuming a request was obeyed.
     *
     * <p>A killed engine is called out because it means the clean path did not work, and a survivor is
     * called out loudly because it is the one case a user may still have to act on — the whole point being
     * that they should never have to guess.
     *
     * <p>Package-private: a synthetic {@link EngineFleet.StopResult} is the only way to exercise the
     * survivor settle without a wedged engine on the machine running the test.
     */
    int report(List<EngineFleet.StopResult> results) {
        if (results.isEmpty()) {
            return settle(Exit.SUCCESS, "no engines running");
        }
        int stopped = 0;
        int killed = 0;
        int draining = 0;
        List<Long> survived = new ArrayList<>();
        for (EngineFleet.StopResult r : results) {
            switch (r.outcome()) {
                case STOPPED -> stopped++;
                case KILLED -> killed++;
                case DRAINING -> draining++;
                case SURVIVED -> survived.add(r.member().pid());
            }
        }
        StringBuilder msg = new StringBuilder();
        int gone = stopped + killed;
        msg.append(gone).append(gone == 1 ? " engine stopped" : " engines stopped");
        if (killed > 0) msg.append(" (").append(killed).append(" needed a hard kill)");
        if (draining > 0) {
            msg.append(", ")
                    .append(draining)
                    .append(" draining (will exit once in-flight jobs finish or the drain deadline passes;"
                            + " `jk engine stop --now` stops now)");
        }
        if (!survived.isEmpty()) {
            msg.append(", ")
                    .append(survived.size())
                    .append(" did NOT exit: pid ")
                    .append(survived);
        }
        return settle(survived.isEmpty() ? Exit.SUCCESS : Exit.FAILURE, msg.toString());
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
