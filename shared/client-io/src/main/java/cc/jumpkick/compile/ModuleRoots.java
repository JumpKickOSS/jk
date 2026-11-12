// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.config.PathTargets;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.layout.LanguageRuntimes;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PathSource;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.resolver.CrossPackageFeatures;
import cc.jumpkick.resolver.TestEngines;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The roots a module's classpath grows from through the lock graph: what the module declares in
 * the classpath's scopes plus what {@code jk lock} adds for it ({@link #own}), and what the
 * workspace siblings it depends on pass on ({@link #inherited}). A workspace lock holds every
 * member's rows; these roots are what keeps one member's classpath to the rows it reaches.
 */
final class ModuleRoots {

    private static final Set<Scope> INHERITED_COMPILE =
            Collections.unmodifiableSet(EnumSet.of(Scope.EXPORT, Scope.MAIN));
    private static final Set<Scope> INHERITED_RUNTIME =
            Collections.unmodifiableSet(EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME));
    private static final Set<Scope> TEST_SCOPES = Collections.unmodifiableSet(EnumSet.of(Scope.TEST, Scope.TEST_DEV));

    private ModuleRoots() {}

    /**
     * The module's own roots over {@code scopes}, as lock row names in declaration order: every
     * declaration but a workspace edge ({@link #addDeclared}), the optional dependencies its path
     * dependencies' feature selections activate, then its language runtime when the scopes hold
     * {@code main}, then the test platform jk adds when they hold {@code test}. {@code rows} is the
     * lock the roots are looked up in.
     */
    static Set<String> own(JkBuild module, @Nullable Path moduleDir, Set<Scope> scopes, List<Lockfile.Artifact> rows) {
        Set<String> roots = new LinkedHashSet<>();
        for (Scope scope : scopes) {
            for (Dependency dep : module.dependencies().of(scope)) addDeclared(roots, dep, moduleDir, rows);
        }
        if (scopes.contains(Scope.MAIN)) {
            addFeatureExtras(roots, module.dependencies().of(Scope.MAIN), moduleDir, rows);
            roots.addAll(LanguageRuntimes.of(module, moduleDir).modules());
        }
        if (scopes.contains(Scope.TEST)) roots.addAll(testPlatform(module, moduleDir, rows));
        return roots;
    }

    /**
     * The roots {@code jk lock} added to the module's test graph, by the rule the lock applied to
     * its solve ({@link TestEngines#injectedKeys}): the walk that asks whether the module reaches a
     * Jupiter API runs over the lock's test-classpath rows ({@link ClasspathResolver#TEST}) from what
     * the module's test declarations and its main and provided ones stand for, as Surefire reads a
     * Jupiter off the whole test classpath.
     */
    static List<String> testPlatform(JkBuild module, @Nullable Path moduleDir, List<Lockfile.Artifact> rows) {
        Set<String> declared = new LinkedHashSet<>();
        for (Scope scope : TEST_SCOPES) {
            for (Dependency dep : module.dependencies().of(scope)) addDeclared(declared, dep, moduleDir, rows);
        }
        Set<String> classpath = new LinkedHashSet<>();
        for (Scope scope : TestEngines.CLASSPATH_SCOPES) {
            for (Dependency dep : module.dependencies().of(scope)) addDeclared(classpath, dep, moduleDir, rows);
        }
        List<Lockfile.Artifact> testRows = new ArrayList<>();
        for (Lockfile.Artifact row : rows) {
            if (row.inAnyScope(ClasspathResolver.TEST)) testRows.add(row);
        }
        return TestEngines.injectedKeys(module, declared, classpath, keys -> {
            Set<String> reached = new HashSet<>();
            for (Lockfile.Artifact row : ClasspathResolver.reachableArtifacts(testRows, keys)) {
                reached.add(row.packageKey());
            }
            return reached;
        });
    }

    /**
     * The roots the workspace siblings {@code module} depends on through {@code scopes} pass on,
     * transitively over their own export and main edges: each sibling's non-optional
     * {@code [export-dependencies]} and {@code [dependencies]}, its {@code [runtime-dependencies]}
     * as well when {@code scopes} is a runtime view ({@link #inheritedScopes}), the optional
     * dependencies its path dependencies' features activate, and its language runtime. A sibling
     * whose fat jar relocates passes nothing on: the jar carries its graph. Empty outside a
     * workspace.
     */
    static Set<String> inherited(
            JkBuild module, @Nullable Path moduleDir, Set<Scope> scopes, List<Lockfile.Artifact> rows) {
        if (moduleDir == null) return Set.of();
        Map<Path, JkBuild> siblings;
        try {
            siblings = WorkspaceClasspath.closureSiblings(moduleDir, module, scopes);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the workspace of " + moduleDir + ": " + e.getMessage(), e);
        }
        Set<Scope> passed = inheritedScopes(scopes);
        Set<String> roots = new LinkedHashSet<>();
        for (Map.Entry<Path, JkBuild> sibling : siblings.entrySet()) {
            JkBuild build = sibling.getValue();
            if (build.relocates()) continue;
            for (Scope scope : passed) {
                for (Dependency dep : build.dependencies().of(scope)) {
                    if (!dep.optional()) addDeclared(roots, dep, sibling.getKey(), rows);
                }
            }
            addFeatureExtras(roots, build.dependencies().of(Scope.MAIN), sibling.getKey(), rows);
            roots.addAll(LanguageRuntimes.of(build, sibling.getKey()).modules());
        }
        return roots;
    }

    /**
     * The scopes of a sibling's rows a consumer's classpath over {@code scopes} reads: export and
     * main for a compile view, runtime as well for a view that runs — a test JVM, an application,
     * an annotation processor.
     */
    static Set<Scope> inheritedScopes(Set<Scope> scopes) {
        boolean runs = scopes.contains(Scope.RUNTIME) || ClasspathResolver.PROCESSOR_PATH.containsAll(scopes);
        return runs ? INHERITED_RUNTIME : INHERITED_COMPILE;
    }

    /**
     * The lock row names a declaration stands for. A coordinate is its package key; a local jar
     * ({@code sha256 =}) is the row {@code jk lock} writes under the declaration's own name; a git
     * dependency is the row stamped with its repository and ref; a path dependency is the
     * coordinate its target publishes, read from the target's {@code jk.toml} with the path resolved
     * from {@code baseDir}, the declaring module's directory, or every row a path build published
     * when the target is a Maven or Gradle project. A workspace edge is not a lock row.
     */
    private static void addDeclared(
            Set<String> roots, Dependency dep, @Nullable Path baseDir, List<Lockfile.Artifact> rows) {
        if (dep.isWorkspace()) return;
        if (dep.isFile()) {
            roots.add(dep.module());
            return;
        }
        GitSource git = dep.gitSource();
        if (git != null) {
            for (Lockfile.Artifact row : rows) {
                Lockfile.Artifact.GitInfo info = row.git();
                if (info != null
                        && info.url().equals(git.canonicalUrl())
                        && Objects.equals(info.ref(), git.ref().token())) {
                    roots.add(row.name());
                }
            }
            return;
        }
        PathSource path = dep.pathSource();
        if (path != null) {
            String coordinate = pathCoordinate(path, baseDir);
            if (coordinate != null) {
                roots.add(coordinate);
            } else {
                for (Lockfile.Artifact row : rows) {
                    if (row.git() == null && row.source().startsWith(RepoArtifactResolver.GIT_SOURCE_PREFIX)) {
                        roots.add(row.name());
                    }
                }
            }
            return;
        }
        String key = dep.packageKey();
        if (!key.isBlank()) roots.add(key);
    }

    /**
     * The optional dependencies a path dependency's feature selection activates in its target,
     * which {@code jk lock} roots in the consumer's main graph.
     */
    private static void addFeatureExtras(
            Set<String> roots, List<Dependency> main, @Nullable Path baseDir, List<Lockfile.Artifact> rows) {
        if (baseDir == null || main.stream().noneMatch(Dependency::hasFeatureSelection)) return;
        for (Dependency extra : CrossPackageFeatures.expand(baseDir, main).extrasList()) {
            addDeclared(roots, extra, baseDir, rows);
        }
    }

    /** The {@code group:artifact} a path dependency's jk target publishes, or null for a foreign target. */
    private static @Nullable String pathCoordinate(PathSource path, @Nullable Path baseDir) {
        return baseDir == null ? null : PathTargets.coordinate(baseDir, path);
    }
}
