// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.BaselineFile;
import cc.jumpkick.guard.baseline.Entry;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Mutation-time enforcement: judge the manifest text a {@code jk add} / {@code jk remove} /
 * {@code jk_deps} is about to write, before it is written. Only the kinds whose substrate is the
 * model run here ({@code depend} and {@code toolchain}). A project without guards
 * pays one stat. There is no {@code --force}: the sanctioned path is a reviewed {@code allow}.
 */
public final class MutationCheck {

    private MutationCheck() {}

    /**
     * @param manifest the {@code jk.toml} being edited
     * @param proposed its full text after the edit
     * @return the refusal to print, or {@code null} when the edit is allowed
     */
    public static @Nullable String check(Path manifest, String proposed) {
        Path dir = manifest.toAbsolutePath().normalize().getParent();
        if (dir == null) return null;
        Path root = WorkspaceScan.findRoot(dir).orElse(dir);
        GuardsConfig cfg;
        try {
            cfg = JkBuildParser.guardsConfig(root.resolve(ManifestPaths.MANIFEST));
        } catch (RuntimeException unparseable) {
            cfg = GuardsConfig.ABSENT;
        }
        if (!GuardsPresence.detect(root, false, cfg.declared())) return null;
        LoadResult load = GuardRules.load(root, cfg);
        if (load.hasErrors()) return null; // the build reports the load errors; an edit is not the place
        List<Rule> rules = new ArrayList<>(load.rules().ofKind(Kind.DEPEND));
        rules.addAll(load.rules().ofKind(Kind.TOOLCHAIN));
        if (rules.isEmpty()) return null;
        String module = root.equals(dir) ? "" : root.relativize(dir).toString().replace('\\', '/');
        Lockfile lock = null;
        Baseline baseline = Baseline.EMPTY;
        try {
            Path lockFile = root.resolve(ManifestPaths.LOCK);
            if (Files.isRegularFile(lockFile)) lock = LockfileReader.read(lockFile);
            baseline = BaselineFile.read(GuardsPresence.baselineFile(root));
        } catch (IOException | RuntimeException e) {
            // A lock that does not read leaves the manifest arms in force and the lock arms out.
        }
        List<String> refusals = new ArrayList<>();
        for (Rule rule : rules) {
            Evaluation e;
            try {
                e = rule.kind() == Kind.TOOLCHAIN
                        ? ToolchainEvaluator.evaluateProposed(rule, module, proposed, lock)
                        : DependEvaluator.evaluateProposed(rule, module, proposed, lock);
            } catch (RuntimeException failed) {
                continue; // an unparseable proposal is the editor's error to report
            }
            if (e.outcome() != Outcome.VIOLATIONS && e.outcome() != Outcome.STALE_ALLOW) continue;
            Set<String> tolerated = new HashSet<>();
            for (Entry en : baseline.of(rule.id()).entries()) tolerated.add(en.key());
            List<Observation> fresh = new ArrayList<>();
            for (Observation o : e.observations()) if (!tolerated.contains(o.key())) fresh.add(o);
            if (fresh.isEmpty()) continue;
            refusals.add(render(rule, fresh));
        }
        return refusals.isEmpty() ? null : String.join("\n", refusals);
    }

    private static String render(Rule rule, List<Observation> fresh) {
        StringBuilder sb = new StringBuilder();
        sb.append("GUARD ").append(rule.id()).append("  refused this edit\n");
        for (Observation o : fresh) sb.append("  ").append(o.detail()).append('\n');
        if (rule.instead() != null)
            sb.append("  Instead:  ").append(rule.instead()).append('\n');
        sb.append("  Why:      ").append(rule.why()).append('\n');
        sb.append("  Exempt:   ask the user to add [guards.")
                .append(rule.id())
                .append("].allow with a reason; there is no --force\n");
        sb.append("  Explain:  jk guard explain ").append(rule.id());
        return sb.toString();
    }
}
