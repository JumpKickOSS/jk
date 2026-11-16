// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.DashboardToken;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.cli.tui.form.Asker;
import cc.jumpkick.cli.tui.form.FormRenderer;
import cc.jumpkick.cli.tui.form.FormSources;
import cc.jumpkick.config.FrontDoorScope;
import cc.jumpkick.form.Form;
import cc.jumpkick.host.Guest;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.winhost.MachineSetup;
import cc.jumpkick.winhost.SetupConsent;
import cc.jumpkick.winhost.SetupEnvironment;
import cc.jumpkick.winhost.SetupMemo;
import cc.jumpkick.winhost.SetupPlan;
import cc.jumpkick.winhost.WindowsFromGuest;
import cc.jumpkick.winhost.WindowsHostCheck;
import cc.jumpkick.winhost.WindowsSetup;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.DecisionsAck;
import cc.jumpkick.wire.protocol.DecisionsRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk doctor --fix}: the recommended Windows host changes as one decision, then the ticked
 * ones applied behind one UAC prompt. The decision is raised in the engine's queue, so the
 * dashboard's bell, a notification and {@code jk decide} show it too. At a terminal it is asked here
 * while the command watches the queue, so an answer from another surface ends the prompt; without a
 * terminal the command waits up to {@link #WAIT} for one. {@code -y}, or {@code [windows] fix =
 * apply}, takes every change without asking; {@code [windows] fix = never} records a decline. In a
 * WSL distro whose Windows side has no JumpKick, the one change is JumpKick for Windows, asked again
 * whatever was answered before and installed here through {@code powershell.exe}.
 */
final class DoctorFix {

    /** How long a run without a terminal waits for another surface to answer. */
    static final Duration WAIT = Duration.ofMinutes(5);

    private static final Duration POLL = Duration.ofMillis(750);

    private DoctorFix() {}

    /** How the decision ended for this run. */
    sealed interface Answer {
        record Accepted(Set<String> chosen) implements Answer {}

        record Declined() implements Answer {}

        /** Nobody answered in time, or the person backed out; the decision stays open. */
        record Open(String id, @Nullable String link) implements Answer {}

        record Failed(String message) implements Answer {}
    }

    static int run(GlobalOptions global) throws IOException {
        if (!Os.isWindows()) {
            Guest guest = Guest.detect();
            if (guest.kind() == Guest.Kind.WSL && guest.interop() && FrontDoorScope.active()) return guest(global);
            CommandWedge.printOk("Doctor", "nothing to set up: the host fixes are for Windows");
            return Exit.SUCCESS;
        }
        // A WSL row installs through the engine, the one operation the dashboard and setup-wsl use.
        List<SetupEnvironment> rows = WindowsSetup.environments(SelfSetupWslCommand::install);
        WindowsHostCheck check = WindowsSection.check();
        List<Path> known = check.project() == null ? List.of() : List.of(check.project());
        SetupPlan plan = WindowsSetup.plan(check, known, rows);
        if (plan.isEmpty()) {
            CommandWedge.printOk("Doctor", "this machine is set up: nothing to change");
            return Exit.SUCCESS;
        }
        SetupConsent consent = WindowsSetup.consent();
        if (consent == SetupConsent.NEVER && !global.yes) {
            WindowsSetup.machine(check, rows).decline(plan, "declined by " + consent.settledBy());
            CommandWedge.printOk("Doctor", "not asked: " + consent.settledBy() + "; -y applies anyway");
            return Exit.SUCCESS;
        }
        Answer answer = global.yes || consent == SetupConsent.APPLY
                ? new Answer.Accepted(WindowsSetup.all(plan))
                : ask(
                        WindowsSetup.QUESTION,
                        WindowsSetup.detail(),
                        WindowsSetup.items(plan),
                        WindowsSetup.YES,
                        check.project(),
                        global);
        switch (answer) {
            case Answer.Declined d -> {
                CommandWedge.printOk("Doctor", "declined; JumpKick keeps working, `jk doctor` notes what is slower");
                return Exit.SUCCESS;
            }
            case Answer.Open o -> {
                String link = DashboardToken.forOutput(o.link(), global, EnginePaths.current());
                CliOutput.out("Still open: answer it with `jk decide " + o.id() + "`"
                        + (link == null ? "" : " or at " + link) + ", then rerun `jk doctor --fix`.");
                return Exit.SUCCESS;
            }
            case Answer.Failed f -> {
                CommandWedge.printFail("Doctor", f.message());
                return Exit.FAILURE;
            }
            case Answer.Accepted a -> {
                MachineSetup.Applied applied = WindowsSetup.machine(check, rows).apply(plan, a.chosen());
                for (MachineSetup.Result r : applied.results())
                    CliOutput.out((r.ok() ? "  ok    " : "  warn  ") + r.line());
                return Exit.SUCCESS;
            }
        }
    }

    /**
     * On a WSL guest: JumpKick for Windows, offered again whatever was answered before, when Windows
     * has none; installed here when accepted. {@code -y} or {@code [windows] fix = apply} installs
     * without asking; {@code never} records a decline.
     */
    private static int guest(GlobalOptions global) throws IOException {
        Path memo = SetupMemo.file(JkDirs.state());
        if (!WindowsFromGuest.missing(WindowsFromGuest.INTEROP)) {
            CommandWedge.printOk(
                    "Doctor", "nothing to set up: Windows has JumpKick, or this distro cannot ask Windows");
            return Exit.SUCCESS;
        }
        SetupConsent consent = WindowsSetup.consent();
        if (consent == SetupConsent.NEVER && !global.yes) {
            WindowsFromGuest.remember(memo, false, Clock.SYSTEM.millis());
            CommandWedge.printOk("Doctor", "not asked: " + consent.settledBy() + "; -y installs anyway");
            return Exit.SUCCESS;
        }
        Answer answer = global.yes || consent == SetupConsent.APPLY
                ? new Answer.Accepted(Set.of(WindowsFromGuest.ROW))
                : ask(
                        WindowsFromGuest.QUESTION,
                        WindowsFromGuest.detail(),
                        List.of(WindowsFromGuest.row().item()),
                        WindowsFromGuest.YES,
                        null,
                        global);
        switch (answer) {
            case Answer.Declined d -> {
                WindowsFromGuest.remember(memo, false, Clock.SYSTEM.millis());
                CommandWedge.printOk("Doctor", "declined; builds here keep this distro's own dashboard");
                return Exit.SUCCESS;
            }
            case Answer.Open o -> {
                CliOutput.out("Still open: answer it with `jk decide " + o.id() + "`"
                        + (o.link() == null ? "" : " or at " + o.link()) + ", then rerun `jk doctor --fix`.");
                return Exit.SUCCESS;
            }
            case Answer.Failed f -> {
                CommandWedge.printFail("Doctor", f.message());
                return Exit.FAILURE;
            }
            case Answer.Accepted a -> {
                if (!a.chosen().contains(WindowsFromGuest.ROW)) {
                    WindowsFromGuest.remember(memo, false, Clock.SYSTEM.millis());
                    CommandWedge.printOk("Doctor", "nothing chosen; nothing changed");
                    return Exit.SUCCESS;
                }
                MachineSetup.Result r =
                        WindowsFromGuest.installFor(JkVersion.VERSION, line -> CliOutput.out("  " + line));
                if (r.ok()) WindowsFromGuest.remember(memo, true, Clock.SYSTEM.millis());
                CliOutput.out((r.ok() ? "  ok    " : "  warn  ") + r.line());
                return r.ok() ? Exit.SUCCESS : Exit.FAILURE;
            }
        }
    }

    /** Raises the decision and waits for its answer: typed here at a terminal, else from any surface. */
    private static Answer ask(
            String question,
            List<String> detail,
            List<Map<String, Object>> items,
            String yes,
            @Nullable Path project,
            GlobalOptions global)
            throws IOException {
        boolean terminal = Interactivity.canPrompt() && !global.outputIsJson();
        EnginePaths.Paths paths = EnginePaths.current();
        Map<String, Object> ask = new LinkedHashMap<>();
        ask.put("kind", WindowsSetup.KIND);
        ask.put("question", question);
        ask.put("detail", detail);
        ask.put("items", items);
        ask.put("yes", yes);
        if (project != null) ask.put("project", project.toString());
        ask.put("alert", !terminal);
        DecisionsAck raised = EngineClient.decisions(
                paths, new DecisionsRequest(DecisionsRequest.RAISE, null, null, MiniJson.write(ask)));
        String raisedJson = raised.decision();
        if (raised.error() != null || raisedJson == null || !(MiniJson.parse(raisedJson) instanceof Map<?, ?> d)) {
            return new Answer.Failed(
                    "could not ask: " + raised.error() + "; `jk doctor --fix -y` applies every change");
        }
        String id = String.valueOf(MiniJson.str(d, "id"));
        DecideView.card(d).forEach(CliOutput::out);
        String link = DashboardToken.forOutput(raised.link(), global, paths);
        CliOutput.out("  answer here, with `jk decide " + id + "`" + (link == null ? "" : ", or at " + link));
        Form form = Form.fromJson(MiniJson.write(d.get("form")));
        Asker asker = terminal && "open".equals(MiniJson.str(d, "state")) ? Asker.interactive() : null;
        CompletableFuture<Optional<Map<String, Object>>> typed = new CompletableFuture<>();
        Thread prompt = null;
        if (asker != null) {
            prompt = Thread.ofPlatform().daemon().start(() -> {
                try {
                    typed.complete(new FormRenderer(asker, FormSources.local(Path.of("."))).run(form, Map.of()));
                } catch (RuntimeException e) {
                    typed.completeExceptionally(e);
                }
            });
        }
        long until = Clock.SYSTEM.nanos() + WAIT.toNanos();
        boolean sent = false;
        while (true) {
            if (typed.isDone() && !typed.isCompletedExceptionally() && !sent) {
                sent = true;
                Optional<Map<String, Object>> values = typed.getNow(Optional.empty());
                if (values.isEmpty()) return new Answer.Open(id, raised.link());
                EngineClient.decisions(
                        paths, new DecisionsRequest(DecisionsRequest.ACCEPT, id, MiniJson.write(values.get()), null));
            }
            Map<?, ?> now = DecideView.find(
                    DecideView.parse(EngineClient.decisions(paths, DecisionsRequest.list())
                            .decisions()),
                    id);
            String state = now == null ? "missing" : MiniJson.str(now, "state");
            if (!"open".equals(state) && prompt != null && !typed.isDone()) {
                prompt.interrupt();
                joinQuietly(prompt);
                CliOutput.out("Answered elsewhere: " + state + ".");
            }
            if (now != null && "accepted".equals(state)) return spend(paths, id, now);
            if ("declined".equals(state)) return new Answer.Declined();
            if (!"open".equals(state)) return new Answer.Failed("the decision " + state + "; nothing changed");
            if (prompt == null && Clock.SYSTEM.nanos() > until) return new Answer.Open(id, raised.link());
            if (typed.isCompletedExceptionally()) return new Answer.Failed("the terminal prompt failed");
            try {
                if (prompt == null || typed.isDone()) Thread.sleep(POLL.toMillis());
                else typed.get(POLL.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException waiting) {
                // Still asking, or the prompt failed: the next round says which.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Answer.Open(id, raised.link());
            }
        }
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Takes the accepted decision for this run; another run that took it first applied it. */
    private static Answer spend(EnginePaths.Paths paths, String id, Map<?, ?> d) throws IOException {
        DecisionsAck spent =
                EngineClient.decisions(paths, new DecisionsRequest(DecisionsRequest.SPEND, id, null, null));
        if (!spent.spent()) return new Answer.Failed("another surface already applied this decision");
        Set<String> chosen = new LinkedHashSet<>();
        if (d.get("answers") instanceof Map<?, ?> a && a.get("changes") instanceof List<?> list) {
            for (Object o : list) chosen.add(String.valueOf(o));
        }
        return new Answer.Accepted(chosen);
    }
}
