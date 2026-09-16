// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.model.ExtensionCapabilities;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.paths.PathList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a {@link LockedClosure.Plan} to a resolved Quarkus application model.
 *
 * <p>Quarkus's bootstrap resolves the model with its own Aether, which is how it finds each
 * extension's <em>deployment</em> artifacts — build-time only, never shipped. What it must not
 * decide is the <em>runtime</em> closure, because that is what lands in {@code quarkus-app/lib} and
 * jk already solved it. So the resolved model is rebuilt here with every runtime dependency pinned
 * to the lock. Deployment-only dependencies ride through untouched.
 *
 * <p>All the judgement lives in {@link LockedClosure#plan}; this class only carries its verdict
 * across into Quarkus's types. The rebuild is a re-wrap, not a re-resolve:
 * {@link ApplicationModelBuilder#build()} folds parent-first / lesser-priority sets and extension
 * exclusion patterns into per-dependency flags before it hands out a model, and those flags are
 * already baked into the dependencies being copied — so carrying the built dependencies across
 * preserves every classloader decision Quarkus made.
 */
final class LockedAppModel {

    /**
     * Every flag bit. {@code getDependenciesWithAnyFlag} is the only accessor that spans both
     * classpaths ({@code getDependencies()} is the deployment one alone), and a dependency with no
     * flags at all is invisible to every consumer including Quarkus's own.
     */
    private static final int ANY_FLAG = -1;

    private LockedAppModel() {}

    /**
     * The resolved model with its runtime closure replaced by jk's.
     *
     * @throws IllegalStateException when the resolver produced a runtime artifact the lock does not
     *     name — that artifact would otherwise ship unlocked
     */
    static ApplicationModel enforce(ApplicationModel resolved, LockedClosure lock) {
        Map<String, ResolvedDependencyBuilder> byCoords = new LinkedHashMap<>();
        for (ResolvedDependency dep : resolved.getDependenciesWithAnyFlag(ANY_FLAG)) {
            byCoords.put(coords(dep.getGroupId(), dep.getArtifactId(), dep.getClassifier(), dep.getType()), copy(dep));
        }

        List<ResolvedDependencyBuilder> runtimeBuilders = new ArrayList<>();
        List<LockedClosure.Resolved> runtime = new ArrayList<>();
        for (ResolvedDependency dep : resolved.getRuntimeDependencies()) {
            runtimeBuilders.add(
                    byCoords.get(coords(dep.getGroupId(), dep.getArtifactId(), dep.getClassifier(), dep.getType())));
            runtime.add(new LockedClosure.Resolved(
                    dep.getGroupId(), dep.getArtifactId(), dep.getClassifier(), dep.getVersion()));
        }
        LockedClosure.Plan plan = lock.plan(runtime);
        report(plan);

        // plan() answers in the order it was asked, so pin i belongs to runtime dependency i.
        for (int i = 0; i < plan.pins().size(); i++) {
            LockedClosure.Pin pin = plan.pins().get(i);
            ResolvedDependencyBuilder builder = runtimeBuilders.get(i);
            if (!pin.ship()) {
                // Off the runtime classpath, so it cannot reach quarkus-app/lib. Its other flags
                // stand: an artifact the deployment side also needs stays available at build time.
                builder.clearFlag(DependencyFlags.RUNTIME_CP);
                continue;
            }
            builder.setVersion(pin.version());
            if (pin.jar() != null) {
                builder.setResolvedPath(pin.jar());
            }
        }

        ApplicationModelBuilder rebuilt = new ApplicationModelBuilder();
        rebuilt.setAppArtifact(copy(resolved.getAppArtifact()));
        rebuilt.setPlatformImports(resolved.getPlatforms());
        for (ExtensionCapabilities capabilities : resolved.getExtensionCapabilities()) {
            rebuilt.addExtensionCapabilities(capabilities);
        }
        rebuilt.addReloadableWorkspaceModules(resolved.getReloadableWorkspaceDependencies());
        resolved.getRemovedResources().forEach(rebuilt::addRemovedResources);
        byCoords.values().forEach(rebuilt::addDependency);
        return rebuilt.build();
    }

    /**
     * The model with {@code module} attached to its application artifact, whose resolved paths
     * become the module's main output — one root for the test bootstrap to index. Everything else
     * is carried across unchanged.
     */
    static ApplicationModel withApplicationModule(ApplicationModel model, WorkspaceModule module) {
        ResolvedDependencyBuilder app = copy(model.getAppArtifact()).setWorkspaceModule(module);
        Path root = module.getMainSources().getSourceDirs().isEmpty()
                ? null
                : module.getMainSources().getSourceDirs().iterator().next().getOutputDir();
        if (root != null) app.setResolvedPath(root);
        List<ResolvedDependencyBuilder> deps = new ArrayList<>();
        for (ResolvedDependency dep : model.getDependenciesWithAnyFlag(ANY_FLAG)) {
            deps.add(copy(dep));
        }
        return rebuild(model, app, deps);
    }

    /**
     * The model with every dependency file under {@code from} copied into {@code to} and pointed
     * there. A resolve downloads what no mirror had into its private local repository, and a model
     * that names those files has to outlive it — {@code to} is a plain directory of the step's
     * output, which the action cache keeps and restores.
     */
    static ApplicationModel withPathsRelocated(ApplicationModel model, Path from, Path to) throws IOException {
        List<ResolvedDependencyBuilder> deps = new ArrayList<>();
        for (ResolvedDependency dep : model.getDependenciesWithAnyFlag(ANY_FLAG)) {
            ResolvedDependencyBuilder builder = copy(dep);
            List<Path> paths = new ArrayList<>();
            boolean moved = false;
            for (Path path : dep.getResolvedPaths()) {
                if (path.startsWith(from) && Files.isRegularFile(path)) {
                    Files.createDirectories(to);
                    Path kept = to.resolve(path.getFileName().toString());
                    Files.copy(path, kept, StandardCopyOption.REPLACE_EXISTING);
                    paths.add(kept);
                    moved = true;
                } else {
                    paths.add(path);
                }
            }
            if (moved) builder.setResolvedPaths(PathList.from(paths));
            deps.add(builder);
        }
        return rebuild(model, copy(model.getAppArtifact()), deps);
    }

    /** {@code model} re-wrapped around {@code app} and {@code deps}; everything else carried across. */
    private static ApplicationModel rebuild(
            ApplicationModel model, ResolvedDependencyBuilder app, List<ResolvedDependencyBuilder> deps) {
        ApplicationModelBuilder rebuilt = new ApplicationModelBuilder();
        rebuilt.setAppArtifact(app);
        rebuilt.setPlatformImports(model.getPlatforms());
        for (ExtensionCapabilities capabilities : model.getExtensionCapabilities()) {
            rebuilt.addExtensionCapabilities(capabilities);
        }
        rebuilt.addReloadableWorkspaceModules(model.getReloadableWorkspaceDependencies());
        model.getRemovedResources().forEach(rebuilt::addRemovedResources);
        deps.forEach(rebuilt::addDependency);
        return rebuilt.build();
    }

    /** Say every disagreement out loud — a lock that quietly loses is the defect being fixed. */
    private static void report(LockedClosure.Plan plan) {
        for (String override : plan.overrides()) {
            System.err.println("jk-quarkus-augment: lock overrides " + override);
        }
        if (!plan.unlocked().isEmpty()) {
            System.err.println("jk-quarkus-augment: NOT shipped — outside jk-lock.toml (declare them under"
                    + " [dependencies] and re-lock to ship them): " + String.join(", ", plan.unlocked()));
        }
        if (!plan.unresolved().isEmpty()) {
            System.err.println("jk-quarkus-augment: locked but off the augment's runtime classpath (extension"
                    + " excluded-artifacts): " + String.join(", ", plan.unresolved()));
        }
    }

    /** Artifact identity as the model keys it: {@code group:artifact:classifier:type}. */
    private static String coords(String group, String artifact, String classifier, String type) {
        return group + ":" + artifact + ":" + classifier + ":" + type;
    }

    /** A builder holding everything the resolver decided about one dependency. */
    private static ResolvedDependencyBuilder copy(ResolvedDependency dep) {
        ResolvedDependencyBuilder copy = ResolvedDependencyBuilder.newInstance()
                .setGroupId(dep.getGroupId())
                .setArtifactId(dep.getArtifactId())
                .setClassifier(dep.getClassifier())
                .setType(dep.getType())
                .setVersion(dep.getVersion())
                .setScope(dep.getScope())
                .setFlags(dep.getFlags())
                .setResolvedPaths(dep.getResolvedPaths())
                .setDependencies(dep.getDependencies())
                .setDirectDependencies(dep.getDirectDependencies());
        for (ArtifactKey exclusion : dep.getExclusions()) {
            copy.addExclusion(exclusion);
        }
        if (dep.getWorkspaceModule() != null) {
            copy.setWorkspaceModule(dep.getWorkspaceModule());
        }
        return copy;
    }
}
