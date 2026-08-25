// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import java.util.Map;

/** The shared summary line for the two confirm-gated machine tools. */
final class MachineActions {

    private MachineActions() {}

    /**
     * An error wins, then an unconfirmed preview, then the action's own name. {@code jk_disk} and
     * {@code jk_jdk} both gate destruction on {@code confirm}, so both must say the same thing
     * when the confirmation is missing.
     */
    static String summary(Map<String, Object> data, String action) {
        if (data.containsKey("error")) return String.valueOf(data.get("error"));
        return Boolean.TRUE.equals(data.get("preview")) ? "confirm required" : action;
    }
}
