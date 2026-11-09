// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpMachine;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.host.Os;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.winhost.HostFacts;
import cc.jumpkick.winhost.HostRow;
import cc.jumpkick.winhost.SetupMemo;
import cc.jumpkick.winhost.WindowsHostCheck;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code doctor} — host health, off the same memoized cache walk as {@code disk}; on Windows also
 * the host conditions that slow or block builds, each with its fix.
 */
public final class DoctorTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "doctor",
                "Host health snapshot (config + disk; on Windows the host rows and their fixes).",
                McpSchemas.object(Map.of()));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        Map<String, Object> body =
                new LinkedHashMap<>(McpMachine.doctor(in.ctx().cacheSnapshot()));
        body.put("windows", windowsRows(in.dir()).stream().map(HostRow::toMap).toList());
        return in.ok(McpEnvelope.of("doctor", body), "doctor");
    }

    /** The Windows rows for the project at {@code dir}; empty off Windows. */
    static List<HostRow> windowsRows(@Nullable String dir) {
        if (!Os.isWindows()) return List.of();
        SetupMemo memo = SetupMemo.read(SetupMemo.file(JkDirs.state()));
        Path project = dir == null ? null : Path.of(dir);
        return new WindowsHostCheck(HostFacts.live(), memo, JkDirs.home(), project).rows();
    }
}
