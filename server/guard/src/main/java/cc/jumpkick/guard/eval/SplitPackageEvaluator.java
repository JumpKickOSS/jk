// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.extract.SourceRoots;
import cc.jumpkick.guard.extract.WorkspaceFacts;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * {@code split-package}: one module owns each package. Read from every module's index in the
 * workspace lane; a package two modules both compile is a violation unless an {@code allow} names
 * the package with the reason it is legal. The fingerprint carries the module pair, so a split that
 * gains a third module is a new site; an allow whose package is no longer split is stale, red. A
 * lane that read no index for a module whose main sources declare the allow's package (a partly
 * built worktree), or whose index predates a source there that its last compile did not read, does
 * not judge that allow: it cannot see whether the split is still there.
 *
 * <p>A module whose share of a split package is generated code only — the package's directory sits
 * under a generated-sources tree ({@code target/generated-sources/*}, {@code target/ksp/*}) and
 * under none of its source roots — does not claim it: processors such as Hilt write one package
 * into every module by design. Nor does a module whose share is only classes of sources that are
 * gone since its last compile ({@link WorkspaceFacts#removedClasses}).
 */
final class SplitPackageEvaluator implements Evaluator {

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) {
        Map<String, Map<String, Path>> dirsByPackage = new TreeMap<>();
        Map<String, Set<Path>> packageDirs = WorkspaceFacts.packageModuleDirs(ctx.root(), ctx.modules());
        Set<Path> indexed = new HashSet<>();
        for (var e : packageDirs.entrySet()) {
            for (Path dir : e.getValue()) {
                indexed.add(dir);
                String module = WorkspaceModel.rel(ctx.root(), dir);
                if (!rule.applies(module)) continue;
                dirsByPackage.computeIfAbsent(e.getKey(), k -> new TreeMap<>()).put(module, dir);
            }
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
        Unseen unseen = new Unseen(ctx, rule, indexed);
        for (var e : allowUsed.entrySet())
            if (!e.getValue() && !unseen.mayDeclare(e.getKey().in()))
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
     * The modules this lane cannot see whole: those it read no index for — not built yet in this
     * worktree, or built before its index could be written — and those whose main sources hold a
     * file their last compile did not read. An allow is judged only by a lane that saw every module
     * that may own its package: while one of these declares a matching package in sources its index
     * does not reflect, the split may still be there, and calling the allow stale would be a guess.
     */
    private static final class Unseen {
        private final List<Path> unindexed = new ArrayList<>();
        private final List<Path> indexed = new ArrayList<>();
        private @Nullable Set<String> packages;

        Unseen(EvalContext ctx, Rule rule, Set<Path> read) {
            for (Path m : ctx.modules()) {
                if (!rule.applies(WorkspaceModel.rel(ctx.root(), m))) continue;
                (read.contains(m) ? indexed : unindexed).add(m);
            }
        }

        /** Whether a package {@code in} names is in sources the lane's indexes do not reflect. */
        boolean mayDeclare(String in) {
            Set<String> declared = packages;
            if (declared == null) {
                declared = sourcePackages(unindexed);
                declared.addAll(unreadPackages(indexed));
                packages = declared;
            }
            for (String pkg : declared) {
                if (pkg.equals(in) || Rule.globMatches(in, pkg)) return true;
            }
            return false;
        }
    }

    /** Every package with at least one file in a main source root of {@code modules}. */
    static Set<String> sourcePackages(List<Path> modules) {
        Set<String> out = new TreeSet<>();
        for (Path module : modules) packagesOf(module, file -> true, out);
        return out;
    }

    /**
     * Every package of a compilable source in a main root of {@code modules} that the stamps of the
     * module's last compile do not name: a source added or moved in since, which its index cannot
     * show. A module with no stamp names nothing it could be behind.
     */
    static Set<String> unreadPackages(List<Path> modules) {
        Set<String> out = new TreeSet<>();
        for (Path module : modules) {
            List<Path> stamped = BuildStamps.stampedSources(
                    BuildLayout.moduleTargetDir(module).resolve("classes"));
            if (stamped.isEmpty()) continue;
            Set<Path> read = new HashSet<>();
            for (Path source : stamped) read.add(source.toAbsolutePath().normalize());
            packagesOf(
                    module,
                    file -> compilable(file)
                            && !read.contains(file.toAbsolutePath().normalize()),
                    out);
        }
        return out;
    }

    private static boolean compilable(Path file) {
        String name = String.valueOf(file.getFileName());
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".groovy");
    }

    /** Adds to {@code out} the package of each file {@code take} accepts in a main source root of {@code module}. */
    private static void packagesOf(Path module, Predicate<Path> take, Set<String> out) {
        for (Path root : SourceRoots.mainRoots(module, ModuleLayout.isCompact(module))) {
            if (!Files.isDirectory(root)) continue;
            try {
                PathUtil.forEachReadableFile(root, (file, attrs) -> {
                    Path dir = root.relativize(file).getParent();
                    if (dir != null && take.test(file))
                        out.add(dir.toString().replace(dir.getFileSystem().getSeparator(), "."));
                });
            } catch (IOException unreadable) {
                // A tree the lane cannot list declares nothing it could defend an allow with.
            }
        }
    }

    /** Whether {@code pkg}'s directory is under one of the module's generated trees and none of its source roots. */
    static boolean generatedOnly(Path moduleDir, String pkg) {
        String pkgPath = pkg.replace('.', '/');
        if (pkgPath.isEmpty()) return false;
        for (Path root : SourceRoots.roots(moduleDir, ModuleLayout.isCompact(moduleDir))) {
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
