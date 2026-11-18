// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.CacheBackend;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.task.RemoteCaches;
import cc.jumpkick.task.RemoteTier;
import cc.jumpkick.task.ToolchainPolicy;
import cc.jumpkick.test.SuiteReadLists;
import cc.jumpkick.test.SuiteReads;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A suite's discovered reads through the build's remote tier (JK-02G9W). A suite's stamp folds in
 * the files it was found to read, which a host learns only by running it; a host that never ran the
 * suite takes the stamp without them (the base) and would run the suite once before it could share.
 * So a writing build sends the read list with the green result, filed under the base, and a host
 * with no list of its own asks the base, recomputes each result's stamp from the files in its own
 * tree, and takes the one whose stamp its tree gives, keeping its list as its own.
 */
final class RemoteSuiteReads {

    private RemoteSuiteReads() {}

    /**
     * Before a writing build stores the green result settled under {@code settled}, note its read
     * list and base for the upload. Nothing when no writing tier is bound or the suite's reads have
     * no portable list ({@link SuiteReadLists#portable}).
     */
    static void offer(
            Path moduleDir, TestSelection selection, @Nullable String settled, Callable<@Nullable String> rekey)
            throws Exception {
        RemoteTier tier = RemoteCaches.current();
        if (tier == null || settled == null || !tier.writes()) return;
        List<String> files = SuiteReadLists.portable(moduleDir, SuiteReads.load(moduleDir, selection));
        if (files == null) return;
        String base =
                SuiteReadLists.<@Nullable String, Exception>as(moduleDir, SuiteReads.Discovered.NONE, rekey::call);
        if (base == null || base.equals(settled)) return;
        tier.noteReads(settled, new CacheBackend.Reads(base, files));
    }

    /**
     * The stamp key this run takes: {@code stampKey}, or, on a host that keeps no reads of its own for
     * the suite and holds no result under {@code stampKey} ({@code held}, here or in the tier), the key of a result the remote tier holds
     * under a read list on {@code stampKey} whose stamp this tree gives. That list is then kept as
     * this host's, so its next run keys the suite the same way.
     */
    static @Nullable String adopt(
            Path moduleDir,
            TestSelection selection,
            @Nullable String stampKey,
            Callable<@Nullable String> rekey,
            Function<String, ? extends Optional<?>> held)
            throws Exception {
        RemoteTier tier = RemoteCaches.current();
        if (tier == null || stampKey == null) return stampKey;
        if (!SuiteReads.load(moduleDir, selection).equals(SuiteReads.Discovered.NONE)) return stampKey;
        if (held.apply(stampKey).isPresent()) return stampKey;
        ToolchainPolicy.Expectation expected = ToolchainPolicy.of(stampKey);
        for (CacheBackend.Candidate c : tier.candidates(stampKey, expected == null ? null : expected.accept())) {
            SuiteReads.Discovered reads =
                    SuiteReadLists.fromPortable(moduleDir, c.reads().files());
            if (reads == null) continue;
            String key = SuiteReadLists.<@Nullable String, Exception>as(moduleDir, reads, rekey::call);
            if (!c.key().equals(key)) continue;
            SuiteReads.save(moduleDir, selection, reads);
            tier.take(c);
            return key;
        }
        return stampKey;
    }
}
