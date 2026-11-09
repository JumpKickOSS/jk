// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
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
 * proposal starts from, and the decline any surface gives, recorded in the setup memo so the machine
 * is not asked again.
 */
public final class MachineSetupDecisions {

    /** How many recent runs name the projects already known. */
    private static final int RECENT_RUNS = 200;

    private MachineSetupDecisions() {}

    /** Records each declined setup decision in the memo, whichever surface declined it. Windows only. */
    public static void watch(DecisionQueue queue) {
        if (!Os.isWindows()) return;
        queue.onChange(d -> {
            if (!WindowsSetup.KIND.equals(d.kind()) || d.state() != Decision.State.DECLINED) return;
            try {
                WindowsHostCheck check = WindowsSetup.check(project(d.project()));
                SetupPlan plan = WindowsSetup.plan(check, served());
                WindowsSetup.machine(check).decline(plan, "declined");
            } catch (IOException | RuntimeException e) {
                Log.warn("could not record the declined machine setup: " + Errors.text(e));
            }
        });
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
