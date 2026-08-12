// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.tui.DrainView;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk engine stop} — graceful drain by default: the engine refuses new jobs and exits cleanly
 * once in-flight jobs finish (its AOT cache still assembles). On a TTY with jobs running it blocks
 * with a live "Draining N job(s)…" region; press Ctrl-X (or pass {@code --force}) to stop now.
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
                Opt.flag("Stop now (abandon jobs; still finish AOT)", "--now"),
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
        Optional<EngineClient.Status> before = EngineClient.status(cc.jumpkick.engine.EnginePaths.activeSocket(paths));
        if (before.isEmpty()) {
            cc.jumpkick.cli.tui.CommandWedge.printOk("Engine", "not running");
            return Exit.SUCCESS;
        }
        long started = before.get().startedAtMillis();

        // Force: stop now, then CONFIRM it went. Reporting "stopped" without checking is how a wedged
        // engine ends up being the user's problem to find and kill.
        if (in.isSet("now")) {
            if (!EngineClient.forceStop(cc.jumpkick.engine.EnginePaths.activeSocket(paths)))
                EngineClient.hardKill(before.get().pid());
            return confirmGone(before.get().pid(), started);
        }

        // Graceful drain. The engine enters draining and reports the in-flight job count.
        int jobs = EngineClient.drain(cc.jumpkick.engine.EnginePaths.activeSocket(paths));
        if (jobs <= 0) {
            // Idle (or already gone): the engine should exit immediately — verify, and escalate if not.
            return confirmGone(before.get().pid(), started);
        }
        if (!BuildPlanConsole.isInteractiveTerminal()) {
            cc.jumpkick.cli.tui.CommandWedge.printOk(
                    "Engine", "shutdown scheduled (" + jobs + " job" + (jobs == 1 ? "" : "s") + " will finish first)");
            return Exit.SUCCESS;
        }
        return drainOnTty(paths, jobs, started);
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
            CliOutput.out(stoppedWedge(elapsed(started)));
            return Exit.SUCCESS;
        }
        EngineClient.hardKill(pid);
        if (EngineFleet.waitForExit(pid)) {
            cc.jumpkick.cli.tui.CommandWedge.printOk(
                    "Engine", "Engine stopped after a hard kill (it did not exit on request).");
            return Exit.SUCCESS;
        }
        cc.jumpkick.cli.tui.CommandWedge.printOk(
                "Engine", "Engine pid " + pid + " did NOT exit, even after a hard kill.");
        return Exit.FAILURE;
    }

    /**
     * Stop one engine by pid — the handle {@code jk engine status} prints.
     *
     * <p>Exists so that clearing a stray engine never means reaching for {@code kill}. On Windows that
     * would mean identifying the right JVM in Task Manager, which is not a reasonable thing to ask.
     */
    private int stopByPid(String raw, boolean now) {
        long pid;
        try {
            pid = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            cc.jumpkick.cli.tui.CommandWedge.printOk("Engine", "not a pid: " + raw);
            return Exit.FAILURE;
        }
        Optional<EngineFleet.StopResult> result = EngineFleet.stopByPid(pid, now);
        if (result.isEmpty()) {
            cc.jumpkick.cli.tui.CommandWedge.printOk("Engine", "no running engine with pid " + pid);
            return Exit.FAILURE;
        }
        return report(List.of(result.get()));
    }

    /**
     * Report what actually happened to each engine, rather than assuming a request was obeyed.
     *
     * <p>A killed engine is called out because it means the clean path did not work, and a survivor is
     * called out loudly because it is the one case a user may still have to act on — the whole point being
     * that they should never have to guess.
     */
    private int report(List<EngineFleet.StopResult> results) {
        if (results.isEmpty()) {
            cc.jumpkick.cli.tui.CommandWedge.printOk("Engine", "no engines running");
            return Exit.SUCCESS;
        }
        int stopped = 0;
        int killed = 0;
        int draining = 0;
        List<Long> survived = new java.util.ArrayList<>();
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
            msg.append(", ").append(draining).append(" draining (will exit once in-flight jobs finish)");
        }
        if (!survived.isEmpty()) {
            msg.append(", ")
                    .append(survived.size())
                    .append(" did NOT exit: pid ")
                    .append(survived);
        }
        cc.jumpkick.cli.tui.CommandWedge.printOk("Engine", msg.toString());
        return survived.isEmpty() ? Exit.SUCCESS : Exit.FAILURE;
    }

    /** Block on a TTY with the live drain region until the engine exits or Ctrl-X forces it. */
    private int drainOnTty(EnginePaths.Paths paths, int jobs, long started) {
        DrainView view = DrainView.start(jobs, GlobalConfig.nerdfont());
        try {
            while (true) {
                if (view.forceRequested()) {
                    EngineClient.forceStop(cc.jumpkick.engine.EnginePaths.activeSocket(paths));
                    break;
                }
                Optional<EngineClient.Status> s =
                        EngineClient.status(cc.jumpkick.engine.EnginePaths.activeSocket(paths));
                if (s.isEmpty()) {
                    // Confirm the engine really exited (avoid a transient accept/close false positive).
                    sleep(150);
                    if (EngineClient.status(cc.jumpkick.engine.EnginePaths.activeSocket(paths))
                                    .isEmpty()
                            && !EngineClient.ping(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) break;
                    continue;
                }
                view.setJobs(Math.max(0, s.get().activeBuildPlans()));
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
                Glyphs.STOP, "Engine", GlobalConfig.nerdfont(), "Engine stopped. Ran for " + uptime(ranMs) + ".");
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
