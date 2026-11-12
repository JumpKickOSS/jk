// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.Request;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The second part of a module's preflight fingerprint: what its suites were found to read ({@link
 * cc.jumpkick.test.SuiteReads}). A suite that finds new inputs and proves them unchanged since
 * before it ran records the digest it ends with, and the clean memo the build certifies takes it in
 * place of the preflight's, so the next build is not scheduled for jk's own bookkeeping.
 *
 * <p>What a build's suites settled is held by its request under {@link #SLOT} and spent by the same
 * build; off a request nothing is recorded, and the next build schedules the module.
 */
public final class SettledReads {

    /** Between a module fingerprint's two parts: everything read at preflight, then the reads digest. */
    static final String READS = "+reads=";

    /** The settled reads of the build a request runs. */
    public static final Request.Slot<SettledReads> SLOT = new Request.Slot<>("settled-reads");

    private record Settled(String before, String after) {}

    private final Map<Path, Settled> settled = new ConcurrentHashMap<>();

    /**
     * The module's suite stored its marker under a key whose discovered inputs digest to {@code
     * after}, proved unchanged since before it ran; the key it started from held {@code before}.
     */
    public static void record(Path moduleDir, String before, String after) {
        SettledReads mine = current();
        if (mine != null) mine.settled.put(moduleDir.toAbsolutePath().normalize(), new Settled(before, after));
    }

    /**
     * {@code fingerprints} with each module's reads part replaced by what its suite settled this
     * build, where the preflight's part is the one the suite started from; any other is kept, and
     * the next preflight schedules that module. The settled entries of these modules are spent.
     */
    public static Map<Path, String> amend(Map<Path, String> fingerprints) {
        Map<Path, String> out = new LinkedHashMap<>(fingerprints);
        SettledReads mine = current();
        if (mine == null) return out;
        for (Map.Entry<Path, String> e : fingerprints.entrySet()) {
            Settled s = mine.settled.remove(e.getKey().toAbsolutePath().normalize());
            if (s == null) continue;
            int at = e.getValue().lastIndexOf(READS);
            if (at < 0 || !e.getValue().substring(at + READS.length()).equals(s.before())) continue;
            out.put(e.getKey(), e.getValue().substring(0, at) + READS + s.after());
        }
        return out;
    }

    private static @Nullable SettledReads current() {
        Request request = Request.current();
        return request == null ? null : request.get(SLOT);
    }
}
