// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.SessionContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * {@code --verify-cache}: a build that runs every step and holds the cache to what it finds.
 *
 * <p>Two claims are checked. A record says "these inputs produce these outputs": every store of a
 * verifying build compares the outputs it just produced with the record already filed under the
 * same key, and a difference fails the step (a poisoned record, or a step that is not
 * reproducible). The preflight says "this module is up to date": the outputs a module it would have
 * skipped held before the build must be the ones the build then produces ({@link #divergences}).
 */
public final class VerifyCache {

    private VerifyCache() {}

    /** Whether this request verifies the cache. */
    public static boolean on() {
        return SessionContext.current().verifyCache();
    }

    /**
     * Hold {@code outputs} against the record already filed under {@code key}: the same outputs, or
     * no record at all. Throws naming the first file that differs.
     */
    static void check(ActionCache cache, String taskId, String key, Map<String, String> outputs) throws IOException {
        ActionCache.ActionRecord prior = cache.lookup(key).orElse(null);
        if (prior == null || prior.outputs().isEmpty() || prior.outputs().equals(outputs)) return;
        Set<String> names = new TreeSet<>(prior.outputs().keySet());
        names.addAll(outputs.keySet());
        for (String name : names) {
            String was = prior.outputs().get(name);
            String now = outputs.get(name);
            if (Objects.equals(was, now)) continue;
            throw new IOException("--verify-cache: " + taskId.split("@", 2)[0] + " under key " + key.substring(0, 8)
                    + " produced " + name + " " + describe(now) + ", but the cache's record for that key holds "
                    + describe(was) + ": the record is poisoned or the step is not reproducible");
        }
    }

    private static String describe(@Nullable String sha) {
        return sha == null ? "nothing" : sha.substring(0, Math.min(12, sha.length()));
    }

    /**
     * Every output in {@code claims} (module to absolute path to digest: what the preflight vouched
     * for before the build) that the build produced otherwise, one sentence each; empty when all
     * held.
     */
    public static List<String> divergences(Map<Path, Map<Path, String>> claims) throws IOException {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Path, Map<Path, String>> module : new TreeMap<>(claims).entrySet()) {
            for (Map.Entry<Path, String> file : new TreeMap<>(module.getValue()).entrySet()) {
                Path f = file.getKey();
                String now = Files.isRegularFile(f) ? FileHashMemo.contentHash(f) : null;
                if (file.getValue().equals(now)) continue;
                out.add("--verify-cache: the preflight called "
                        + module.getKey().getFileName()
                        + " up to date, but a fresh build " + (now == null ? "does not produce " : "changes ")
                        + f.getFileName());
            }
        }
        return out;
    }
}
