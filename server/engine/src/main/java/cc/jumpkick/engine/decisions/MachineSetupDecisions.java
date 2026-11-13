// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.winhost.MachineSetup;
import cc.jumpkick.winhost.SetupConsent;
import cc.jumpkick.winhost.SetupMemo;
import cc.jumpkick.winhost.SetupOffer;
import cc.jumpkick.winhost.SetupPlan;
import cc.jumpkick.winhost.WindowsHostCheck;
import cc.jumpkick.winhost.WindowsSetup;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The machine-setup decision on the engine's side: the projects this engine has built, which the
 * proposal starts from; the offer a person's build makes once; applying an offered decision the
 * person accepted, since the build that offered it has exited; and the decline or expiry any
 * surface gives, recorded in the setup memo so the machine is not asked again.
 */
public final class MachineSetupDecisions {

    /** How many recent runs name the projects already known. */
    private static final int RECENT_RUNS = 200;

    /** Who raised an offered decision, as every surface shows it; the engine applies those it raised. */
    static final String OFFERED_BY = "jk build";

    private MachineSetupDecisions() {}

    /**
     * Records each declined or expired setup decision in the memo, whichever surface settled it, and
     * applies an offered one the person accepted. Windows only.
     */
    public static void watch(DecisionQueue queue) {
        if (!Os.isWindows()) return;
        queue.onChange(d -> {
            if (!WindowsSetup.KIND.equals(d.kind())) return;
            switch (d.state()) {
                case DECLINED -> decline(d.project(), "declined");
                case EXPIRED -> decline(d.project(), "not answered");
                case ACCEPTED -> {
                    if (OFFERED_BY.equals(d.label())) spendAndApply(queue, d);
                }
                case OPEN -> {}
            }
        });
    }

    /**
     * A person's build offers the setup decision: raised as a notification, applied, or declined as
     * {@code [windows] fix} says, and only on a machine that never answered it. Off the request path,
     * since the plan reads the host. An accepted decision nobody applied is applied now.
     */
    public static void offer(DecisionQueue queue, @Nullable Path project) {
        if (!Os.isWindows()) return;
        if (!SetupMemo.read(SetupMemo.file(JkDirs.state())).mayAsk()) return;
        SessionContext.startVirtual("machine-setup-offer", () -> {
            try {
                offerNow(queue, project);
            } catch (RuntimeException e) {
                Log.debug("machine setup offer failed", e);
            }
        });
    }

    private static void offerNow(DecisionQueue queue, @Nullable Path project) {
        for (Decision d : queue.list()) {
            if (WindowsSetup.KIND.equals(d.kind()) && d.accepted() && !queue.spent(d.id())) {
                spendAndApply(queue, d);
                return;
            }
        }
        boolean outstanding = queue.list().stream().anyMatch(d -> WindowsSetup.KIND.equals(d.kind()) && d.open());
        WindowsHostCheck check = WindowsSetup.check(project);
        SetupPlan plan = WindowsSetup.plan(check, served());
        SetupConsent consent = WindowsSetup.consent();
        switch (SetupOffer.of(consent, check.memo(), outstanding, plan.isEmpty())) {
            case NONE -> {}
            case DECLINE -> decline(project, "declined by " + consent.settledBy());
            case APPLY -> apply(check, plan, WindowsSetup.all(plan));
            case ASK -> {
                Decision d = queue.open(new DecisionQueue.Ask(
                        WindowsSetup.KIND,
                        WindowsSetup.QUESTION,
                        WindowsSetup.detail(),
                        DecisionForms.batch(
                                WindowsSetup.KIND,
                                WindowsSetup.QUESTION,
                                plan.changes().stream()
                                        .map(c -> new DecisionForms.Item(c.id(), c.title(), c.why() + " " + c.effect()))
                                        .toList(),
                                WindowsSetup.YES),
                        null,
                        OFFERED_BY,
                        null,
                        project == null ? null : project.toString()));
                queue.alert(d);
            }
        }
    }

    private static void spendAndApply(DecisionQueue queue, Decision d) {
        if (!queue.spend(d.id())) return;
        SessionContext.startVirtual("machine-setup-apply", () -> {
            WindowsHostCheck check = WindowsSetup.check(project(d.project()));
            apply(check, WindowsSetup.plan(check, served()), new LinkedHashSet<>(DecisionForms.chosen(d.answers())));
        });
    }

    private static void apply(WindowsHostCheck check, SetupPlan plan, Set<String> chosen) {
        try {
            MachineSetup.Applied applied = WindowsSetup.machine(check).apply(plan, chosen);
            for (MachineSetup.Result r : applied.results()) Log.info("machine setup: " + r.line());
        } catch (IOException | RuntimeException e) {
            Log.warn("could not apply the machine setup: " + Errors.text(e));
        }
    }

    private static void decline(@Nullable String project, String line) {
        decline(project(project), line);
    }

    private static void decline(@Nullable Path project, String line) {
        try {
            WindowsHostCheck check = WindowsSetup.check(project);
            SetupPlan plan = WindowsSetup.plan(check, served());
            WindowsSetup.machine(check).decline(plan, line);
        } catch (IOException | RuntimeException e) {
            Log.warn("could not record the declined machine setup: " + Errors.text(e));
        }
    }

    /** The projects this engine's recent runs built, newest first. */
    public static List<Path> served() {
        Set<Path> out = new LinkedHashSet<>();
        try {
            for (BuildJournal.Head h : BuildJournal.current().heads(RECENT_RUNS)) {
                String dir = h.record().dir();
                if (!dir.isBlank()) out.add(Path.of(dir));
            }
        } catch (RuntimeException unreadable) {
            return new ArrayList<>(out);
        }
        return new ArrayList<>(out);
    }

    public static @Nullable Path project(@Nullable String dir) {
        return dir == null || dir.isBlank() ? null : Path.of(dir);
    }
}
