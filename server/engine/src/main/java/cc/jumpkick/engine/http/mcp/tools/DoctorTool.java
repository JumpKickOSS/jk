// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpMachine;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code jk_doctor} — host health, off the same memoized cache walk as {@code jk_disk}. */
public final class DoctorTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec("jk_doctor", "Host health snapshot (config + disk).", McpSchemas.object(Map.of()));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        return in.ok(McpEnvelope.of("doctor", McpMachine.doctor(in.ctx().cacheSnapshot())), "doctor");
    }
}
