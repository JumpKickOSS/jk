// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.layout.LanguageRuntimes;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.resolver.TestEngines;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The roots a module's classpath grows from through the lock graph: what the module declares in
 * the classpath's scopes plus what {@code jk lock} adds for it ({@link #own}), and what the
 * workspace siblings it depends on pass on ({@link #inherited}). A workspace lock holds every
 * member's rows; these roots are what keeps one member's classpath to the rows it reaches.
 */
final class ModuleRoots {

    private static final Set<Scope> INHERITED_COMPILE = EnumSet.of(Scope.EXPORT, Scope.MAIN);
    private static final Set<Scope> INHERITED_RUNTIME = EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME);

    private ModuleRoots() {}

    /**
     * The module's own roots over {@code scopes}, as lock package keys in declaration order: its
     * external declarations, then its language runtime when the scopes hold {@code main}, then the
     * test platform jk adds when they hold {@code test}. Workspace, git, path and file entries are
     * not lock rows and are left out.
     */
    static Set<String> own(JkBuild module, @Nullable Path moduleDir, Set<Scope> scopes) {
        Set<String> roots = new LinkedHashSet<>();
        for (Scope scope : scopes) {
            for (Dependency dep : module.dependencies().of(scope)) addExternal(roots, dep);
        }
        if (scopes.contains(Scope.MAIN))
            roots.addAll(LanguageRuntimes.of(module, moduleDir).modules());
        if (scopes.contains(Scope.TEST)) {
            for (Dependency dep : TestEngines.injectedRoots(module)) roots.add(dep.packageKey());
        }
        return roots;
    }

    /**
     * The roots the workspace siblings {@code module} depends on through {@code scopes} pass on,
     * transitively over their own export and main edges: each sibling's non-optional
     * {@code [export-dependencies]} and {@code [dependencies]}, its {@code [runtime-dependencies]}
     * as well when {@code scopes} is a runtime view ({@link #inheritedScopes}), and its language
     * runtime. A sibling whose fat jar relocates passes nothing on: the jar carries its graph.
     * Empty outside a workspace.
     */
    static Set<String> inherited(JkBuild module, @Nullable Path moduleDir, Set<Scope> scopes) {
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
                    if (!dep.optional()) addExternal(roots, dep);
                }
            }
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

    private static void addExternal(Set<String> roots, Dependency dep) {
        if (dep.isWorkspace() || dep.isGit() || dep.isPath() || dep.isFile()) return;
        String key = dep.packageKey();
        if (!key.isBlank()) roots.add(key);
    }
}
