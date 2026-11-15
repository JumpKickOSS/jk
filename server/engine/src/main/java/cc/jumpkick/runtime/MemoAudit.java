// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.KeyAudit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The preflight memo's part of the key audit ({@link KeyAudit}): the row a clean memo certifies for
 * a module is the key that decides the module is up to date, so an audited build holds each one to
 * the fingerprint taken again once the build has ended.
 */
public final class MemoAudit {

    private MemoAudit() {}

    /** The audit's name for the memo row that says a module is up to date: not a step, the preflight's verdict. */
    public static final String MEMO_STEP = "preflight-memo";

    /**
     * An audited build ({@link KeyAudit}) holds every row its memo certifies, {@code certified}, to
     * the module's fingerprint taken again once the build has ended: a row the next preflight would
     * not match schedules a module nothing changed, as the guard salt digesting a pack the first
     * build unpacked did (JK-D15HE). {@code preflight} is what the snapshot took, whose tokens the
     * audit kept. Each module's manifest is read again from its directory, as the next preflight reads it.
     */
    public static void auditCertified(boolean skipTests, Map<Path, String> preflight, Map<Path, String> certified) {
        if (!KeyAudit.on()) return;
        for (Map.Entry<Path, String> row : certified.entrySet()) {
            Path dir = row.getKey().toAbsolutePath().normalize();
            String fp = row.getValue();
            Map<String, String> tokens =
                    new LinkedHashMap<>(KeyAudit.remembered(preflight.getOrDefault(row.getKey(), fp)));
            int at = fp.lastIndexOf(SettledReads.READS);
            if (at >= 0) tokens.put("suite reads", fp.substring(at + SettledReads.READS.length()));
            KeyAudit.keyed(dir, MEMO_STEP, new KeyAudit.Keyed(fp, tokens), () -> {
                Path file = ManifestPaths.manifestIn(dir);
                JkBuild manifest = Files.isRegularFile(file) ? JkBuildParser.parse(file) : null;
                PreflightMemo.Fingerprint[] taken = new PreflightMemo.Fingerprint[1];
                KeyAudit.Keyed now = PreflightMemo.tokens(
                        dir, manifest, skipTests, ModuleFingerprints.guardSaltFor(dir, new LinkedHashMap<>()), taken);
                if (now != null) return now;
                String why = taken[0] instanceof PreflightMemo.Uncertain un ? un.reason() : "no fingerprint";
                return new KeyAudit.Keyed("(" + why + ")", Map.of());
            });
        }
    }
}
