// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ToolIdentity;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Where a guard lane's verdict is kept under its lane key, for the lane and the forecast alike. */
final class GuardVerdicts {

    private GuardVerdicts() {}

    /**
     * The token holding the digest of what a module's guard suite reads beyond the module. One
     * token, so {@link #heldTokens} can stand for every value of it.
     */
    static final String SUITE_READS = "suite-reads";

    /** The record input listing the tools a lane's verdict stands on the absence of. */
    static final String TOOLS = "tools";

    /**
     * The verdict a lane keyed {@code key} reached, for the run and the forecast alike. A lane whose
     * guards skipped for want of tools stores, under its key, only the names of those tools, and its
     * verdict under {@link #toolKey}: while none of them is installed the verdict holds, and
     * installing one is a miss that has the guards judged.
     */
    static Optional<ActionCache.ActionRecord> lookup(ActionCache cache, String key, Path root) throws IOException {
        return lookup(cache, key, root, suitePath());
    }

    /** As {@link #lookup(ActionCache, String, Path)}, resolving the tools on {@code path}. */
    static Optional<ActionCache.ActionRecord> lookup(ActionCache cache, String key, Path root, @Nullable String path)
            throws IOException {
        Optional<ActionCache.ActionRecord> found = cache.lookup(key);
        if (found.isEmpty()) return found;
        String tools = found.get().inputs().get(TOOLS);
        return tools == null ? found : cache.lookup(toolKey(key, List.of(tools.split(",")), root, path));
    }

    /**
     * {@code key} with the identity of each of {@code tools} folded in, as the PATH the guard suite's
     * JVM runs with resolves it ({@link ToolIdentity}): {@code (not on PATH)} for a missing one.
     */
    static String toolKey(String key, List<String> tools, Path root) {
        return toolKey(key, tools, root, suitePath());
    }

    /** The PATH a guard suite's JVM is forked with, and so the one its guards look their tools up on. */
    private static @Nullable String suitePath() {
        // Windows spells it Path; the name is case-insensitive there.
        for (var e : WorkerEnv.strict().environment().entrySet()) {
            if (e.getKey().equalsIgnoreCase("PATH")) return e.getValue();
        }
        return null;
    }

    /** As {@link #toolKey(String, List, Path)}, resolving the tools on {@code path}. */
    static String toolKey(String key, List<String> tools, Path root, @Nullable String path) {
        List<String> lines = new ArrayList<>(List.of(key));
        for (String tool : tools) lines.add("tool:" + tool + "=" + ToolIdentity.of(tool, path, root));
        return Hashing.sha256Hex(String.join("\n", lines));
    }

    /**
     * A lane's {@code tokens} with the {@link #SUITE_READS} value held apart, or {@code null} when
     * its guard suite reads nothing beyond the module. Every verdict of a suite that reads the
     * workspace is also recorded under the lane key of these tokens ({@link #storeHeld}), so a
     * forecast whose lane key missed and that finds that record knows the module's own inputs are
     * those of a verdict some checkout reached: only what the suite reads moved. It is found by
     * content, as the verdict is, whichever checkout of the project moved the lane's pointer last.
     */
    static @Nullable List<String> heldTokens(List<String> tokens) {
        String prefix = SUITE_READS + ":";
        List<String> held = new ArrayList<>(tokens.size());
        boolean reads = false;
        for (String token : tokens) {
            reads |= token.startsWith(prefix);
            held.add(token.startsWith(prefix) ? prefix + "*" : token);
        }
        return reads ? held : null;
    }

    /**
     * Records that lane {@code taskId} reached a verdict over the tokens {@code heldKey} keys ({@link
     * #heldTokens}). Its pointer is its own, with the lane's tag: the lane's pointer, and the miss
     * note its next run reads off it, stay on the verdict, and cleaning the project takes both.
     */
    static void storeHeld(ActionCache cache, String taskId, String heldKey) throws IOException {
        int at = taskId.indexOf('@');
        String task = "guard-held" + (at < 0 ? "" : taskId.substring(at));
        cache.storeVerdict(task, heldKey, Map.of(SUITE_READS, "*"));
    }
}
