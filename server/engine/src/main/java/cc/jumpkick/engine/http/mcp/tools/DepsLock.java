// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.runtime.LockFlow;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The one-line manifest edit, then a keep-pins relock so the next {@code run} sees a current lock. */
final class DepsLock {

    private DepsLock() {}

    /** What {@code jk.toml} gained or lost, one line. */
    static String changedLine(Map<String, Object> data) {
        if (data.get("notes") instanceof List<?> notes && !notes.isEmpty()) {
            return notes.stream().map(String::valueOf).collect(Collectors.joining("; "));
        }
        return Boolean.TRUE.equals(data.get("changed")) ? "edited jk.toml" : "unchanged";
    }

    /**
     * Relock {@code dir} and say {@code lock ok}, or the resolve failure in the same shape as a
     * failed lock run. A lock that is already current is {@code lock ok} without a second resolve.
     */
    static String relock(Path dir) {
        try {
            LockFlow.Result result = LockFlow.run(dir, JkDirs.cache(), List.of(), false, null);
            if (result.status() == 0 && !LockFreshness.needsRefresh(dir)) return "lock ok";
            String error = result.error() == null ? "lock failed" : result.error();
            return failure(dir, error);
        } catch (Exception e) {
            return failure(dir, Errors.text(e));
        }
    }

    /** {@code FAIL lock <dir>} plus the first lines of the resolver's message. */
    static String failure(Path dir, String error) {
        String leaf = dir.getFileName() == null ? "" : dir.getFileName().toString();
        StringBuilder sb = new StringBuilder("FAIL lock");
        if (!leaf.isEmpty()) sb.append(' ').append(leaf);
        int n = 0;
        for (String raw : error.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            sb.append('\n');
            if (n == 0) sb.append("E ").append(line);
            else sb.append("  ").append(line);
            if (++n == 3) break;
        }
        if (n == 0) sb.append("\nE resolve failed");
        return sb.toString();
    }
}
