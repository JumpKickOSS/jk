// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.config.WorkspaceModules;
import cc.jumpkick.layout.Languages;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime jars for one module: lockfile transitive closure of declared external deps (and of
 * workspace siblings' main/export/runtime externals) plus sibling thin jars. Shared by packaging
 * (assembly) and thin-worker install — never the whole workspace lock.
 */
public final class ModuleRuntimeClasspath {

    private ModuleRuntimeClasspath() {}

    /**
     * @param moduleDir module root (directory with {@code jk.toml})
     * @param project parsed module manifest
     * @param lockFile workspace or project {@code jk-lock.toml} (may be missing)
     * @param cas content-addressed store used to resolve lock checksums to jar paths
     */
    public static List<Path> jars(Path moduleDir, JkBuild project, Path lockFile, Cas cas) throws IOException {
        List<Path> depJars = new ArrayList<>();
        if (lockFile == null || !Files.exists(lockFile)) {
            try {
                WorkspaceClasspath.Result siblings =
                        WorkspaceClasspath.resolve(moduleDir, project, Set.of(Scope.EXPORT, Scope.MAIN));
                depJars.addAll(siblings.jars());
            } catch (Exception ignored) {
                /* best-effort */
            }
            return depJars;
        }
        ClasspathResolver resolver = new ClasspathResolver(cas);
        Lockfile lock = LockfileReader.read(lockFile);
        WorkspaceClasspath.Result siblings =
                WorkspaceClasspath.resolve(moduleDir, project, Set.of(Scope.EXPORT, Scope.MAIN));

        LinkedHashSet<String> roots = new LinkedHashSet<>();
        roots.addAll(ClasspathResolver.declaredExternalRoots(project, ClasspathResolver.RUNTIME));
        // Language runtimes are lock-injected (LockOrchestrator) but not always declared in
        // jk.toml — seed them so assembly/fat jars nest groovy/kotlin-stdlib.
        seedLanguageRuntimeRoots(moduleDir, project, roots);
        for (JkBuild sib : siblingBuilds(moduleDir, project, siblings.siblingCoords())) {
            roots.addAll(
                    ClasspathResolver.declaredExternalRoots(sib, EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME)));
        }
        depJars.addAll(resolver.classpathClosure(lock, roots, ClasspathResolver.RUNTIME));
        for (Path j : siblings.jars()) {
            if (!depJars.contains(j)) depJars.add(j);
        }
        return depJars;
    }

    /** Convenience when the lock path should be derived via {@link LockPaths#lockFile}. */
    public static List<Path> jars(Path moduleDir, JkBuild project, Cas cas) throws IOException {
        return jars(moduleDir, project, LockPaths.lockFile(moduleDir), cas);
    }

    /**
     * Seed lock GAs for language runtimes when the module uses that language (inject-only deps).
     * Missing lock rows are skipped by {@link ClasspathResolver#classpathClosure}.
     */
    static void seedLanguageRuntimeRoots(Path moduleDir, JkBuild project, Set<String> roots) {
        if (project == null || roots == null) return;
        Languages langs = Languages.resolve(project.project(), moduleDir);
        if (langs.groovy()) roots.add("org.apache.groovy:groovy");
        if (langs.kotlin()) roots.add("org.jetbrains.kotlin:kotlin-stdlib");
        if (langs.scala()) roots.add("org.scala-lang:scala3-library_3");
    }

    static List<JkBuild> siblingBuilds(Path moduleDir, JkBuild project, List<String> siblingCoords) throws IOException {
        if (siblingCoords == null || siblingCoords.isEmpty()) return List.of();
        Set<String> want = new HashSet<>(siblingCoords);
        Path root;
        JkBuild rootManifest;
        if (project.isWorkspaceRoot()) {
            root = moduleDir;
            rootManifest = project;
        } else {
            var rootOpt = WorkspaceLocator.findRoot(moduleDir);
            if (rootOpt.isEmpty()) return List.of();
            root = rootOpt.get();
            rootManifest = JkBuildParser.parse(root.resolve(ManifestPaths.MANIFEST));
            if (!rootManifest.isWorkspaceRoot()) return List.of();
        }
        Workspace workspace = Objects.requireNonNull(rootManifest.workspace(), "workspace root without [workspace]");
        List<JkBuild> out = new ArrayList<>();
        for (String moduleName : WorkspaceModules.expand(root, workspace.modules())) {
            Path unitDir = root.resolve(moduleName);
            Path manifest = unitDir.resolve(ManifestPaths.MANIFEST);
            if (!Files.isRegularFile(manifest)) continue;
            JkBuild unit;
            try {
                unit = JkBuildParser.parse(manifest);
            } catch (RuntimeException e) {
                continue;
            }
            String coord = unit.project().group() + ":" + unit.project().name();
            if (want.contains(coord)) out.add(unit);
        }
        String rootCoord =
                rootManifest.project().group() + ":" + rootManifest.project().name();
        if (want.contains(rootCoord)) out.add(rootManifest);
        return out;
    }
}
