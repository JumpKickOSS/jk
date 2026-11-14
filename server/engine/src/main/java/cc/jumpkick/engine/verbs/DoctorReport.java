// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.doctor.DoctorChecks;
import cc.jumpkick.engine.decisions.MachineSetupDecisions;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.winhost.HostRow;
import cc.jumpkick.winhost.WindowsSetup;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The health report MCP {@code doctor} and {@code GET /api/doctor} answer: the {@link DoctorChecks}
 * rows {@code jk doctor} prints, answered in the engine, the engine's own row, the Windows host rows
 * with their fixes, and the client's own rows marked unavailable rather than left out.
 */
public final class DoctorReport {

    private DoctorReport() {}

    /** The rows for {@code dir} (the project whose lock is checked; {@code null}: none named). */
    public static List<HostRow> rows(@Nullable String dir, String version) {
        List<HostRow> rows = new ArrayList<>();
        Path project = dir == null || dir.isBlank() ? null : PathUtil.resolveUserPath(dir);
        if (project != null) {
            rows.addAll(DoctorChecks.host(project, version));
        } else {
            for (HostRow row : DoctorChecks.host(PathUtil.resolveUserPath("."), version)) {
                rows.add(
                        "lock".equals(row.id()) || "m2".equals(row.id())
                                ? new HostRow(
                                        row.id(), row.label(), HostRow.Status.UNAVAILABLE, "no project named", null)
                                : row);
            }
        }
        rows.add(new HostRow("engine", "engine", HostRow.Status.OK, "answering · " + version, null));
        for (String id : DoctorChecks.CLIENT_LOCAL) rows.add(DoctorChecks.unavailable(id));
        rows.addAll(windowsRows(dir));
        return rows;
    }

    /** The Windows host rows for the project at {@code dir}, each with its fix; empty off Windows. */
    public static List<HostRow> windowsRows(@Nullable String dir) {
        if (!Os.isWindows()) return List.of();
        return WindowsSetup.check(MachineSetupDecisions.project(dir)).rows();
    }

    /** {@code rows} as the JSON object REST answers and MCP returns. */
    public static Map<String, Object> structured(List<HostRow> rows) {
        boolean failed = rows.stream().anyMatch(r -> r.status() == HostRow.Status.FAIL);
        return Map.of("ok", !failed, "rows", rows.stream().map(HostRow::toMap).toList());
    }
}
