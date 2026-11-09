// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** The preflight memo's module fingerprints, taken side by side, and the guard salt they carry. */
final class ModuleFingerprints {

    private ModuleFingerprints() {}

    /** Below this many modules fingerprinting on the caller is cheaper than a hop to the pool. */
    private static final int PARALLEL_FROM = 16;

    /**
     * Every unit's fingerprint, by normalized directory. Each reads only its own module's trees, so
     * a wide workspace fingerprints them side by side: one after another, Helidon's 223 modules
     * were most of a leaf edit's half-second cache check. The guard salts are computed first, on
     * the caller, into {@code saltByRoot}.
     */
    static Map<Path, PreflightMemo.Fingerprint> all(
            List<BuildGraph.BuildUnit> units, boolean skipTests, Map<Path, String> saltByRoot) {
        Map<Path, String> salts = new LinkedHashMap<>();
        for (BuildGraph.BuildUnit u : units) {
            Path dir = u.dir().toAbsolutePath().normalize();
            salts.put(dir, guardSaltFor(dir, saltByRoot));
        }
        Map<Path, PreflightMemo.Fingerprint> out = new ConcurrentHashMap<>();
        if (units.size() < PARALLEL_FROM) {
            for (BuildGraph.BuildUnit u : units) {
                Path dir = u.dir().toAbsolutePath().normalize();
                out.put(
                        dir,
                        PreflightMemo.fingerprintModule(
                                dir, u.manifest(), skipTests, Objects.requireNonNull(salts.get(dir))));
            }
            return out;
        }
        List<CompletableFuture<Void>> pending = new ArrayList<>(units.size());
        for (BuildGraph.BuildUnit u : units) {
            Path dir = u.dir().toAbsolutePath().normalize();
            String salt = Objects.requireNonNull(salts.get(dir));
            pending.add(CompletableFuture.runAsync(
                    () -> out.put(dir, PreflightMemo.fingerprintModule(dir, u.manifest(), skipTests, salt)),
                    JkThreads.cpu()));
        }
        for (var f : pending) {
            try {
                f.join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            }
        }
        return out;
    }

    static String guardSaltFor(Path dir, Map<Path, String> saltByRoot) {
        Path root = WorkspaceScan.findRoot(dir).orElse(dir).toAbsolutePath().normalize();
        return saltByRoot.computeIfAbsent(root, ModuleFingerprints::guardSalt);
    }

    /**
     * The guard rule, baseline and fixture files as a module input: an edit to any of them re-plans
     * every module, or the lanes and proofs never run again. Empty for a workspace without guards.
     */
    static String guardSalt(Path root) {
        if (!PlannerGuards.enabledAt(root)) return "";
        try {
            MessageDigest md = Hashing.newSha256();
            PreflightMemo.feedFile(md, GuardsPresence.rulesFile(root));
            PreflightMemo.feedFile(md, GuardsPresence.baselineFile(root));
            PreflightMemo.feed(md, GuardFixtures.digest(root));
            return "guards=" + Hashing.hex(md.digest());
        } catch (IOException e) {
            return "guards=unreadable";
        }
    }

    /** Every module's fingerprint now: {@link PreflightMemo#snapshotFingerprints}. */
    static PreflightMemo.Snapshot snapshot(BuildGraph.Result graph, boolean skipTests) {
        Map<Path, String> fps = new LinkedHashMap<>();
        Map<Path, PreflightMemo.Uncertain> uncertain = new LinkedHashMap<>();
        Map<Path, String> saltByRoot = new LinkedHashMap<>();
        Map<Path, PreflightMemo.Fingerprint> current = all(graph.topoOrder(), skipTests, saltByRoot);
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            Path dir = u.dir().toAbsolutePath().normalize();
            switch (Objects.requireNonNull(current.get(dir))) {
                case PreflightMemo.Known k -> fps.put(dir, k.hex());
                case PreflightMemo.Uncertain u2 -> uncertain.put(dir, u2);
            }
        }
        return new PreflightMemo.Snapshot(fps, uncertain);
    }
}
