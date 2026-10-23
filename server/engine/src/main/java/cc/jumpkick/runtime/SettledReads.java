// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The second part of a module's preflight fingerprint: what its suites were found to read ({@link
 * cc.jumpkick.test.SuiteReads}). A suite that finds new inputs and proves them unchanged since
 * before it ran records the digest it ends with, and the clean memo the build certifies takes it in
 * place of the preflight's, so the next build is not scheduled for jk's own bookkeeping.
 */
public final class SettledReads {

    /** Between a module fingerprint's two parts: everything read at preflight, then the reads digest. */
    static final String READS = "+reads=";

    private record Settled(String before, String after) {}

    private static final Map<Path, Settled> SETTLED = new ConcurrentHashMap<>();

    private SettledReads() {}

    /**
     * The module's suite stored its marker under a key whose discovered inputs digest to {@code
     * after}, proved unchanged since before it ran; the key it started from held {@code before}.
     */
    public static void record(Path moduleDir, String before, String after) {
        SETTLED.put(moduleDir.toAbsolutePath().normalize(), new Settled(before, after));
    }

    /**
     * {@code fingerprints} with each module's reads part replaced by what its suite settled this
     * build, where the preflight's part is the one the suite started from; any other is kept, and
     * the next preflight schedules that module. The settled entries of these modules are spent.
     */
    public static Map<Path, String> amend(Map<Path, String> fingerprints) {
        Map<Path, String> out = new LinkedHashMap<>(fingerprints);
        for (Map.Entry<Path, String> e : fingerprints.entrySet()) {
            Settled settled = SETTLED.remove(e.getKey().toAbsolutePath().normalize());
            if (settled == null) continue;
            int at = e.getValue().lastIndexOf(READS);
            if (at < 0 || !e.getValue().substring(at + READS.length()).equals(settled.before())) continue;
            out.put(e.getKey(), e.getValue().substring(0, at) + READS + settled.after());
        }
        return out;
    }
}
