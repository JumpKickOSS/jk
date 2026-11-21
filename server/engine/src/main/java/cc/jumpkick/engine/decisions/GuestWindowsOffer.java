// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.config.FrontDoorScope;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Guest;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.winhost.MachineSetup;
import cc.jumpkick.winhost.SetupChange;
import cc.jumpkick.winhost.SetupConsent;
import cc.jumpkick.winhost.SetupMemo;
import cc.jumpkick.winhost.WindowsFromGuest;
import cc.jumpkick.winhost.WindowsSetup;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A WSL guest's offer of JumpKick for Windows: after a person's build, when Windows has no
 * JumpKick and the person never answered, the engine raises the setup decision with the one row
 * "JumpKick for Windows" as a notification (a Windows toast through interop, which opens this
 * engine's dashboard on it), once. Accepting installs it through {@code powershell.exe} as {@link
 * WindowsFromGuest} does; declining, or leaving it unanswered until it expires, is remembered in the
 * guest's setup memo, so no build asks again ({@code jk doctor --fix} does).
 *
 * <p>Only the person's own engine on a WSL guest with interop offers ({@link FrontDoorScope}); whether
 * Windows has JumpKick is asked through interop off the request path and kept a few minutes, so a
 * build's end never waits on {@code cmd.exe}.
 */
public final class GuestWindowsOffer {

    /** How long an answer to "does Windows have JumpKick" is taken as current. */
    static final Duration FRESH = Duration.ofMinutes(5);

    /** Installs JumpKick for Windows, each step to {@code progress}. */
    @FunctionalInterface
    public interface Installer {
        MachineSetup.Result install(Consumer<String> progress);
    }

    private final Path memoFile;
    private final BooleanSupplier windowsMissing;
    private final Installer installer;
    private final Clock clock;
    private final AtomicBoolean asking = new AtomicBoolean();
    private final AtomicBoolean installing = new AtomicBoolean();
    private volatile @Nullable Boolean missing;
    private volatile long askedAt;

    GuestWindowsOffer(Path memoFile, BooleanSupplier windowsMissing, Installer installer, Clock clock) {
        this.memoFile = memoFile;
        this.windowsMissing = windowsMissing;
        this.installer = installer;
        this.clock = clock;
    }

    /**
     * The offer for this engine, or null where it makes none: not a WSL guest with interop, or not the
     * person's own engine. {@code version} is this engine's, which the install is pinned to.
     */
    public static @Nullable GuestWindowsOffer forThisEngine(Guest guest, String version) {
        if (guest.kind() != Guest.Kind.WSL || !guest.interop() || !FrontDoorScope.active()) return null;
        GuestWindowsOffer offer = new GuestWindowsOffer(
                SetupMemo.file(JkDirs.state()),
                () -> WindowsFromGuest.missing(WindowsFromGuest.INTEROP),
                progress -> WindowsFromGuest.installFor(version, progress),
                Clock.SYSTEM);
        offer.refresh();
        return offer;
    }

    /** Asks interop again, off the caller's thread, unless an ask is already under way. */
    void refresh() {
        if (!asking.compareAndSet(false, true)) return;
        SessionContext.startVirtual("windows-jumpkick-check", () -> {
            try {
                missing = windowsMissing.getAsBoolean();
                askedAt = clock.millis();
            } catch (RuntimeException e) {
                Log.debug("could not tell whether Windows has JumpKick", e);
            } finally {
                asking.set(false);
            }
        });
    }

    /** Whether Windows lacked JumpKick when last asked; null before the first answer. Stale answers ask again. */
    @Nullable
    Boolean missing() {
        Boolean known = missing;
        if (known == null || clock.millis() - askedAt > FRESH.toMillis()) refresh();
        return known;
    }

    /**
     * A person's build asks for the offer: the decision opened (and alerted) when the row was never
     * answered, none is out and Windows lacks JumpKick; null otherwise. {@code [windows] fix = never}
     * records a decline instead and {@code apply} installs without asking. An accepted decision nobody
     * applied is applied now.
     */
    public @Nullable Decision offer(DecisionQueue queue, @Nullable String project) {
        if (WindowsFromGuest.answered(memoFile)) return null;
        for (Decision d : queue.list()) {
            if (!WindowsSetup.KIND.equals(d.kind())) continue;
            if (d.open()) return null;
            if (d.accepted() && !queue.spent(d.id())) {
                spendAndInstall(queue, d);
                return null;
            }
        }
        if (!Boolean.TRUE.equals(missing())) return null;
        SetupConsent consent = WindowsSetup.consent();
        switch (consent) {
            case NEVER -> {
                remember(false, "declined by " + consent.settledBy());
                return null;
            }
            case APPLY -> {
                SessionContext.startVirtual("windows-jumpkick-install", () -> installNow());
                return null;
            }
            case ASK -> {}
        }
        SetupChange row = WindowsFromGuest.row();
        Decision d = queue.open(new DecisionQueue.Ask(
                WindowsSetup.KIND,
                WindowsFromGuest.QUESTION,
                WindowsFromGuest.detail(),
                DecisionForms.batch(
                        WindowsSetup.KIND,
                        WindowsFromGuest.QUESTION,
                        List.of(new DecisionForms.Item(row.id(), row.title(), row.why() + " " + row.effect())),
                        WindowsFromGuest.YES),
                null,
                MachineSetupDecisions.OFFERED_BY,
                null,
                project));
        queue.alert(d);
        return d;
    }

    /**
     * Installs an offered decision the person accepted with the row ticked, and remembers every answer:
     * accepted, declined, or expired unanswered (taken as declined, so the next build does not ask).
     */
    public void watch(DecisionQueue queue) {
        queue.onChange(d -> {
            if (!WindowsSetup.KIND.equals(d.kind())) return;
            switch (d.state()) {
                case DECLINED -> remember(false, "declined");
                case EXPIRED -> remember(false, "not answered");
                case ACCEPTED -> {
                    if (MachineSetupDecisions.OFFERED_BY.equals(d.label())) spendAndInstall(queue, d);
                }
                case OPEN -> {}
            }
        });
    }

    private void spendAndInstall(DecisionQueue queue, Decision d) {
        if (!queue.spend(d.id())) return;
        if (!DecisionForms.chosen(d.answers()).contains(WindowsFromGuest.ROW)) {
            remember(false, "left out");
            return;
        }
        SessionContext.startVirtual("windows-jumpkick-install", this::installNow);
    }

    /** Whether an install is under way: it ends after the answer is remembered in the memo. */
    boolean installing() {
        return installing.get();
    }

    private void installNow() {
        if (!installing.compareAndSet(false, true)) return;
        try {
            MachineSetup.Result r = installer.install(line -> Log.info("JumpKick for Windows: " + line));
            Log.info("JumpKick for Windows: " + r.line());
            ChangeJournal.note("JumpKick for Windows", "install", r.line());
            if (r.ok()) missing = false;
            remember(r.ok(), r.line());
        } finally {
            installing.set(false);
        }
    }

    private void remember(boolean accepted, String why) {
        try {
            WindowsFromGuest.remember(memoFile, accepted, clock.millis());
            Log.info("JumpKick for Windows offer answered: " + why);
        } catch (IOException | RuntimeException e) {
            Log.warn("could not record the JumpKick for Windows answer: " + Errors.text(e));
        }
    }
}
