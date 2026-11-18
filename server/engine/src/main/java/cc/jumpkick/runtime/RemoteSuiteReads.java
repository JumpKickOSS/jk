// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.CacheBackend;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.task.KeyAudit;
import cc.jumpkick.task.RemoteCaches;
import cc.jumpkick.task.RemoteTier;
import cc.jumpkick.task.ToolchainPolicy;
import cc.jumpkick.test.SuiteEnvReads;
import cc.jumpkick.test.SuiteReadLists;
import cc.jumpkick.test.SuiteReads;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
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
     * no portable list ({@link SuiteReadLists#portable}). The list names the environment reads the
     * module's {@code policy} keys and no other ({@link SuiteEnvReads.Record#under}).
     */
    static void offer(
            Path moduleDir,
            TestSelection selection,
            @Nullable String settled,
            Callable<@Nullable String> rekey,
            SuiteEnvReads.Policy policy)
            throws Exception {
        RemoteTier tier = RemoteCaches.current();
        if (tier == null || settled == null || !tier.writes()) return;
        SuiteReads.Discovered kept = SuiteReads.load(moduleDir, selection);
        List<String> files = SuiteReadLists.portable(
                moduleDir,
                new SuiteReads.Discovered(
                        kept.files(), kept.commands(), kept.hosts(), kept.env().under(policy)));
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
        List<String> differed = new ArrayList<>();
        for (CacheBackend.Candidate c : tier.candidates(stampKey, expected == null ? null : expected.accept())) {
            SuiteReads.Discovered reads =
                    SuiteReadLists.fromPortable(moduleDir, c.reads().files());
            if (reads == null) continue;
            KeyAudit.Keyed keyed = KeyAudit.tapped(
                    () -> SuiteReadLists.<@Nullable String, Exception>as(moduleDir, reads, rekey::call));
            String key = keyed == null ? null : keyed.key();
            if (!c.key().equals(key)) {
                if (keyed != null) differed.addAll(programsDiffering(c, keyed.tokens()));
                continue;
            }
            SuiteReads.save(moduleDir, selection, reads);
            tier.take(c);
            return key;
        }
        // No result held under this tree's key, and the one on offer started another version of a
        // program: the miss names it, not as a first run (no-record).
        if (!differed.isEmpty()) RemoteTier.candidateDiffered(String.join(", ", new TreeSet<>(differed)));
        return stampKey;
    }

    /**
     * The programs ({@code x:proc:} inputs) {@code c}'s record names with another identity than this
     * host's key over the same read list gives, by input name; empty when its record does not read.
     */
    static List<String> programsDiffering(CacheBackend.Candidate c, Map<String, String> here) {
        Map<String, String> there;
        try {
            there = ActionRecordText.parse(c.recordText(), c.key()).inputs();
        } catch (RuntimeException unreadable) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : there.entrySet()) {
            if (e.getKey().startsWith(PROGRAM) && !e.getValue().equals(here.get(e.getKey()))) out.add(e.getKey());
        }
        for (String name : here.keySet()) {
            if (name.startsWith(PROGRAM) && !there.containsKey(name)) out.add(name);
        }
        return out;
    }

    /** The input name prefix of a program a suite started. */
    private static final String PROGRAM = "x:proc:";
}
