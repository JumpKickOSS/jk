// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.KeyAuditMode;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.task.KeyAudit;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * The end of an audited build ({@code JK_KEY_AUDIT}): once it has succeeded, every key its steps
 * used is taken again ({@link KeyAudit#verify}), each one that moved is said as a {@link
 * KeyAudit#MOVED} warning on its module, and under {@link KeyAuditMode#STRICT} the build fails.
 */
@NullMarked
final class KeyAudits {

    private KeyAudits() {}

    /** {@code result} once the audit of {@code run} has spoken; the result itself when nothing moved. */
    static WorkspaceResult report(WorkspaceRunPhase.Run run, WorkspaceResult result, WorkspaceBuildListener listener) {
        KeyAudit audit = KeyAudit.current();
        if (audit == null || !result.success()) return result;
        long started = Clock.SYSTEM.nanos();
        List<KeyAudit.Moved> moved = audit.verify();
        Log.info(
                "key audit",
                "steps",
                audit.size(),
                "moved",
                moved.size(),
                "ms",
                (Clock.SYSTEM.nanos() - started) / 1_000_000);
        if (moved.isEmpty()) return result;
        List<String> errors = new ArrayList<>();
        for (KeyAudit.Moved m : moved) {
            String module = coord(run, m.module());
            listener.onModuleWarning(m.module(), module, m.step(), KeyAudit.MOVED, m.describe());
            errors.add(module + ": " + m.describe());
        }
        if (audit.mode() != KeyAuditMode.STRICT) return result;
        errors.add(
                0,
                "JK_KEY_AUDIT=strict: " + moved.size() + (moved.size() == 1 ? " step's key" : " steps' keys")
                        + " moved after the build that used them; the next build of this unchanged tree runs them again");
        return new WorkspaceResult(false, Exit.FAILURE, result.modules(), List.copyOf(errors), false);
    }

    /** The module's coordinate as its plan names it, else its directory's name. */
    private static String coord(WorkspaceRunPhase.Run run, Path dir) {
        for (ModulePlan p : run.prepared().plans().values()) {
            if (p.dir().toAbsolutePath().normalize().equals(dir)) return p.coord();
        }
        Path name = dir.getFileName();
        return name == null ? dir.toString() : name.toString();
    }
}
