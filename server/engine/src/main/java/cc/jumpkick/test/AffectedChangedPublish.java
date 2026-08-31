// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.AffectedChanged;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.WorkspaceLocator;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A dirty module's half of cross-module affected ranking (JK-2606): right after compile — when the
 * pre-compile ABI baseline still exists in memory — classify this module's dirty production types
 * and publish {@code FQC → kind} onto the invocation's {@link AffectedChanged} carrier. Dependent
 * modules' {@code run-tests} (always after their dependencies' compiles) read the carrier to rank
 * importers of these types.
 */
public final class AffectedChangedPublish {

    private AffectedChangedPublish() {}

    public static void publish(
            Session session,
            Path moduleDir,
            Map<String, ClassAbi.Fingerprint> preCompileAbi,
            Map<String, ClassAbi.Fingerprint> currentAbi) {
        if (session == null || !session.affected()) return;
        try {
            Path module = moduleDir.toAbsolutePath().normalize();
            Path root = WorkspaceLocator.findRoot(module)
                    .orElse(module)
                    .toAbsolutePath()
                    .normalize();
            List<String> dirty = session.affectedChanged().dirtyPaths(root);
            if (dirty == null) return; // no git — the run path refuses or ranks from compiled sources
            classifyInto(session.affectedChanged(), root, module, dirty, preCompileAbi, currentAbi);
        } catch (Exception e) {
            // Best-effort: a failed classification degrades a dependent's ranking, never the build.
        }
    }

    /** Classify {@code moduleDir}'s dirty production types into {@code sink}. Pure of Session. */
    public static void classifyInto(
            AffectedChanged sink,
            Path workspaceRoot,
            Path moduleDir,
            List<String> dirty,
            Map<String, ClassAbi.Fingerprint> preCompileAbi,
            Map<String, ClassAbi.Fingerprint> currentAbi) {
        Path module = moduleDir.toAbsolutePath().normalize();
        Path root = workspaceRoot.toAbsolutePath().normalize();
        for (String raw : dirty) {
            if (raw == null || raw.isBlank()) continue;
            Path p = Path.of(raw);
            if (!p.isAbsolute()) p = root.resolve(p);
            p = p.normalize();
            if (!p.startsWith(module)) continue;
            String name = p.getFileName() == null ? "" : p.getFileName().toString();
            if (name.equals("package-info.java") || name.equals("module-info.java")) continue;
            if (!(name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".groovy"))) continue;
            String rel = module.relativize(p).toString().replace('\\', '/');
            if (AffectedTestRanker.isTestSource(rel) || !AffectedTestRanker.isMainSource(rel)) continue;
            String fqc = AffectedTestRanker.fqcFromSource(rel);
            if (fqc == null) continue;
            ClassAbi.Fingerprint cur = currentAbi == null ? null : currentAbi.get(fqc);
            ClassAbi.Kind kind = cur == null
                    ? ClassAbi.Kind.ABI
                    : ClassAbi.classify(preCompileAbi == null ? null : preCompileAbi.get(fqc), cur);
            sink.put(fqc, kind.name());
        }
    }

    /** The carrier's snapshot as ranker input, minus this module's own production types. */
    public static Map<String, ClassAbi.Kind> foreignFor(Session session, Set<String> localProduction) {
        if (session == null) return Map.of();
        return foreignFor(session.affectedChanged(), localProduction);
    }

    /** As {@link #foreignFor(Session, Set)} for a bare carrier (the list path's local one). */
    public static Map<String, ClassAbi.Kind> foreignFor(AffectedChanged carrier, Set<String> localProduction) {
        Map<String, String> all = carrier.snapshot();
        if (all.isEmpty()) return Map.of();
        var out = new LinkedHashMap<String, ClassAbi.Kind>();
        for (var e : all.entrySet()) {
            if (localProduction != null && localProduction.contains(e.getKey())) continue;
            out.put(e.getKey(), AffectedChanged.KIND_ABI.equals(e.getValue()) ? ClassAbi.Kind.ABI : ClassAbi.Kind.BODY);
        }
        return out;
    }
}
