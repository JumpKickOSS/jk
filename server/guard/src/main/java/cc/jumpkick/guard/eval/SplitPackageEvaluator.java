// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.extract.WorkspaceFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * {@code split-package}: one module owns each package. Read from every module's index in the
 * workspace lane; a package two modules both compile is a violation unless an {@code allow} names
 * the package with the reason it is legal. The fingerprint carries the module pair, so a split that
 * gains a third module is a new site; an allow whose package is no longer split is stale, red. A
 * lane that read no index for a module whose main sources declare the allow's package (a partly
 * built worktree) does not judge that allow: it cannot see whether the split is still there.
 *
 * <p>A module whose share of a split package is generated code only — the package's directory sits
 * under a generated-sources tree ({@code target/generated-sources/*}, {@code target/ksp/*}) and
 * under none of its source roots — does not claim it: processors such as Hilt write one package
 * into every module by design.
 */
final class SplitPackageEvaluator implements Evaluator {

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) {
        Map<String, Map<String, Path>> dirsByPackage = new TreeMap<>();
        Map<String, Path> classDirs = WorkspaceFacts.classModuleDirs(ctx.root(), ctx.modules());
        for (var e : classDirs.entrySet()) {
            String module = WorkspaceModel.rel(ctx.root(), e.getValue());
            if (!rule.applies(module)) continue;
            dirsByPackage
                    .computeIfAbsent(Descriptors.packageOf(e.getKey()), k -> new TreeMap<>())
                    .put(module, e.getValue());
        }
        Map<String, Set<String>> modulesByPackage = new TreeMap<>();
        for (var e : dirsByPackage.entrySet()) {
            Set<String> owners = new TreeSet<>(e.getValue().keySet());
            if (owners.size() > 1) {
                for (var m : e.getValue().entrySet()) {
                    if (generatedOnly(m.getValue(), e.getKey())) owners.remove(m.getKey());
                }
            }
            modulesByPackage.put(e.getKey(), owners);
        }
        Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        for (Allow a : rule.allow()) allowUsed.put(a, false);
        List<Observation> sites = new ArrayList<>();
        for (var e : modulesByPackage.entrySet()) {
            if (e.getValue().size() < 2) continue;
            Allow a = null;
            for (Allow candidate : rule.allow()) {
                if (candidate.in().equals(e.getKey()) || Rule.globMatches(candidate.in(), e.getKey())) a = candidate;
            }
            if (a != null) {
                allowUsed.put(a, true);
                continue;
            }
            sites.add(Observation.site(
                    e.getKey() + " | " + String.join(" + ", e.getValue()),
                    null,
                    0,
                    e.getKey() + " is declared by " + String.join(" + ", e.getValue())
                            + "; move it to one module, or allow it with a reason"));
        }
        Map<String, Long> population = Map.of("packages", (long) modulesByPackage.size());
        if (modulesByPackage.isEmpty())
            return new Evaluation(Outcome.BLIND, population, List.of(), "no module index to read");
        List<String> stale = new ArrayList<>();
        Unindexed unindexed = new Unindexed(ctx, rule, Set.copyOf(classDirs.values()));
        for (var e : allowUsed.entrySet())
            if (!e.getValue() && !unindexed.mayDeclare(e.getKey().in()))
                stale.add(e.getKey().in());
        if (!stale.isEmpty()) {
            return new Evaluation(
                    Outcome.STALE_ALLOW,
                    population,
                    sites,
                    "allow entries name packages that are no longer split: " + String.join(", ", stale));
        }
        return Evaluation.of(population, sites);
    }

    /**
     * The modules this lane read no index for — not built yet in this worktree, or built before its
     * index could be written. An allow is judged only by a lane that saw every module that may own
     * its package: while one of these declares a matching package in its main sources, the split
     * may still be there, and calling the allow stale would be a guess.
     */
    private static final class Unindexed {
        private final List<Path> modules = new ArrayList<>();
        private @Nullable Set<String> packages;

        Unindexed(EvalContext ctx, Rule rule, Set<Path> indexed) {
            for (Path m : ctx.modules()) {
                if (!indexed.contains(m) && rule.applies(WorkspaceModel.rel(ctx.root(), m))) modules.add(m);
            }
        }

        /** Whether an unindexed module's main sources hold a package {@code in} names. */
        boolean mayDeclare(String in) {
            if (modules.isEmpty()) return false;
            Set<String> declared = packages;
            if (declared == null) packages = declared = sourcePackages(modules);
            for (String pkg : declared) {
                if (pkg.equals(in) || Rule.globMatches(in, pkg)) return true;
            }
            return false;
        }
    }

    /** Every package with at least one file in a main source root of {@code modules}. */
    static Set<String> sourcePackages(List<Path> modules) {
        Set<String> out = new TreeSet<>();
        for (Path module : modules) {
            for (Path root : SourcePaths.mainRoots(module, ModuleLayout.isCompact(module))) {
                if (!Files.isDirectory(root)) continue;
                try {
                    PathUtil.forEachReadableFile(root, (file, attrs) -> {
                        Path dir = root.relativize(file).getParent();
                        if (dir != null)
                            out.add(dir.toString().replace(dir.getFileSystem().getSeparator(), "."));
                    });
                } catch (IOException unreadable) {
                    // A tree the lane cannot list declares nothing it could defend an allow with.
                }
            }
        }
        return out;
    }

    /** Whether {@code pkg}'s directory is under one of the module's generated trees and none of its source roots. */
    static boolean generatedOnly(Path moduleDir, String pkg) {
        String pkgPath = pkg.replace('.', '/');
        if (pkgPath.isEmpty()) return false;
        for (Path root : SourcePaths.roots(moduleDir, ModuleLayout.isCompact(moduleDir))) {
            if (Files.isDirectory(root.resolve(pkgPath))) return false;
        }
        for (Path root : generatedRoots(moduleDir)) {
            if (Files.isDirectory(root.resolve(pkgPath))) return true;
        }
        return false;
    }

    /** Every {@code target/generated-sources/*} directory, then KSP's {@code target/ksp/java} and {@code target/ksp/kotlin}. */
    private static List<Path> generatedRoots(Path moduleDir) {
        Path target = BuildLayout.moduleTargetDir(moduleDir);
        List<Path> out = new ArrayList<>();
        try {
            PathUtil.forEachChild(target.resolve("generated-sources"), (child, attrs) -> {
                if (attrs.isDirectory()) out.add(child);
                return true;
            });
        } catch (IOException unreadable) {
            // An unreadable tree holds no generated package the rule could discount.
        }
        out.add(target.resolve("ksp").resolve("java"));
        out.add(target.resolve("ksp").resolve("kotlin"));
        return out;
    }
}
