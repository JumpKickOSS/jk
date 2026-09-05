// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.ModuleLayoutPlugins;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginModule;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.surface.TrainLayout;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Every packaging action key that both the build and {@code jk explain} have to agree on, derived
 * once.
 *
 * <p>{@code jk explain} prices a step by recomputing the key the build will compute. A prefix-set
 * guard ({@code checkForecastKeyParity}) can prove the two sides emit the same token <em>kinds</em>,
 * but not that they put the same <em>value</em> behind a kind — and values legitimately differ per
 * module, so no text scan can. Two live defects were exactly that shape:
 *
 * <ul>
 * <li><b>.</b> Both {@code package-assembly} sites emitted {@code main:}; the build read
 *     {@code project.mainClass()} and the forecast {@code PluginModule.mainClass(dir, project)},
 *     which answers {@code WORKER_MAIN} for a plugin worker. A worker module with
 *     {@code assembly = true} could therefore never forecast up-to-date.
 * <li><b>.</b> A module packaged by a plugin (spring-boot, grails, quarkus, minified) runs
 *     the packager under a token bag that has nothing to do with the plain jar's, and the forecast
 *     had no arm for it at all — it priced {@code package-jar} against the plain-jar key, which
 *     that build never computes.
 * </ul>
 *
 * <p>The fix for both is the same and it is structural rather than asserted: one body per derived
 * value, called from both sides. That is what this class is. The guard's job shrinks to proving no
 * second body appears.
 */
public final class PackagingKeys {

    private PackagingKeys() {}

    /** A task id, the token bag it was keyed from (the action record stores it), and the key. */
    public record Keyed(String taskId, List<String> tokens, String key) {}

    /**
     * The {@code Main-Class} a packaged artifact carries, and therefore the {@code main:} token in
     * its key: the {@code [application]} main, except for a plugin worker, whose process entry is
     * the fixed {@link PluginModule#WORKER_MAIN} host and is not authored in {@code jk.toml}.
     *
     * <p>One body for the thin jar, the fat jar and the forecast of each. The value is a
     * <em>property of the artifact</em>, so the packager writes the same string this token names —
     * a worker's {@code -all.jar} and its thin jar now agree on their entry point instead of the
     * fat one silently shipping none.
     */
    public static @Nullable String mainClass(Path moduleDir, JkBuild project) {
        return PluginModule.mainClass(moduleDir, project);
    }

    // ---- package-assembly ------------------------------------------------------------------

    /**
     * The fat jar's key. {@code classesTok}/{@code depsTok} are passed in because the two sides
     * legitimately compute them differently — the forecast has to reconstruct a {@code jk
     * clean}-wiped tree from action records — while everything else, including which tokens exist
     * and in what shape, is derived here.
     */
    public static Keyed assembly(
            Path assemblyJar, Path moduleDir, JkBuild project, String classesTok, String contribTok, String depsTok) {
        // The fat jar is a pure function of the main classes, the plugin-contributed dirs merged
        // over them, the bundled dependency jars' content, the main class, and the manifest.
        List<String> tokens = List.of(
                "classes:" + classesTok,
                "contrib:" + contribTok,
                "deps:" + depsTok,
                "main:" + orEmpty(mainClass(moduleDir, project)),
                "manifest:" + project.manifest(),
                "packaging:fat"); // distinct from shrink / thin package-jar
        String taskId = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_ASSEMBLY, assemblyJar);
        String asmKey = ActionKey.forArtifact(taskId, BuildIdentity.cacheKeyVersion(), tokens);
        return new Keyed(taskId, tokens, asmKey);
    }

    /**
     * Whether {@code package-assembly}'s action cache holds a hit for the key the live step will
     * compute. Dep jars must come from {@link PlannerSupport#assemblyDependencyJars} — never the
     * whole workspace lock RUNTIME set, or explain permanently shows "repackage" after a warm
     * assembly. Sibling jars missing after clean are fingerprinted via CAS shas recovered from
     * each sibling's package record.
     */
    static boolean assemblyActionCached(
            Path dir,
            JkBuild project,
            BuildLayout layout,
            Path lockFile,
            ActionCache actionCache,
            Path cache,
            @Nullable String compileMainKey,
            Map<Path, String> restoredJarShas,
            @Nullable Boolean knownResourceDrift)
            throws IOException {
        String classesTok = classesTokenForPackage(
                dir,
                CompileSupport.isSimpleLayout(project.project(), dir),
                layout,
                project,
                actionCache,
                compileMainKey,
                knownResourceDrift);
        // Same jar set as PlannerTails.assemblyStep (ModuleRuntimeClasspath).
        List<Path> depJars = PlannerSupport.assemblyDependencyJars(dir, project, lockFile, cache);
        PluginBuild.Declarations pkgDecls;
        try {
            pkgDecls = PlannerSupport.pluginDeclarationsFor(project, layout, cache);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        Keyed keyed = assembly(
                layout.assemblyJar(),
                dir,
                project,
                classesTok,
                PlannerSupport.contributionsToken(PlannerSupport.existingContributedDirs(pkgDecls, layout)),
                fingerprintDepJars(depJars, actionCache, restoredJarShas));
        return TaskForecaster.present(actionCache, keyed.key());
    }

    /**
     * Content fingerprint of dep jars matching {@link ClasspathFingerprint#of}, recovering sibling
     * jars wiped by {@code jk clean} from the CAS shas the walk pinned off each sibling's current
     * package record.
     */
    static String fingerprintDepJars(List<Path> depJars, ActionCache actionCache, Map<Path, String> restoredJarShas)
            throws IOException {
        List<String> parts = new ArrayList<>(depJars.size());
        for (Path jar : depJars) {
            parts.add(fingerprintJarOrCached(jar, actionCache, restoredJarShas));
        }
        parts.sort(Comparator.naturalOrder());
        return Hashing.sha256Hex(String.join("\n", parts));
    }

    static String fingerprintJarOrCached(Path jar, ActionCache actionCache, Map<Path, String> restoredJarShas)
            throws IOException {
        if (Files.isRegularFile(jar)) {
            return ClasspathFingerprint.entry(jar);
        }
        // After clean: sibling jars live under target/ — recover content from the sha the walk
        // pinned when the sibling's CURRENT package key hit. The pinned sha names a payload blob
        // in the ACTION-CACHE pool (cache tier), not the artifact store. An unpinned wiped jar
        // stays missing:… (assembly forecasts RUN — pessimistic, never a false hit): an
        // unvalidated last-record pointer could name a different edit of the sibling.
        String sha = restoredJarShas.get(jar.toAbsolutePath().normalize());
        if (sha != null) {
            Path blob = actionCache.cas().pathFor(sha);
            if (Files.isRegularFile(blob)) {
                // The blob path would classify as "cas:<abs>", but the live step fingerprinted the
                // on-disk sibling as "file:<content sha>" — return that form so a post-clean
                // assembly forecast can match the stored key.
                return "file:" + sha;
            }
        }
        return ClasspathFingerprint.entry(jar); // missing:…
    }

    // ---- plugin packager (spring-boot / grails / quarkus / minified / android) ---------------

    /** Everything the plugin-packager key and the packager's spec are both derived from. */
    public record Packager(
            JkBuild project,
            Path moduleDir,
            Path cache,
            Path lockFile,
            Cas cas,
            BuildLayout layout,
            Path classes,
            Path artifact,
            Path javaHome,
            PluginBuild.@Nullable Active active,
            PluginBuild.Declarations decls,
            Map<String, String> secrets) {}

    /**
     * The plugin packager's key, plus the three derived values the packager's spec also needs.
     * They are returned rather than recomputed because the facts that reach the plugin body and
     * the facts that key its output must be the same object — a fact that reaches the packager
     * without reaching its key is a stale artifact waiting to be restored.
     */
    public record PackagerKey(
            Keyed keyed, ProjectFacts facts, List<PluginBuild.ProdEntry> entries, Map<String, Path> extras) {}

    /**
     * Action key for packaging owned by a plugin: the declared inputs + the facts + the packager's
     * own identity and code. Any config, classes, dependency-set, step-output, extra-artifact,
     * JDK or manifest change re-packages; nothing else does.
     */
    public static PackagerKey pluginPackager(Packager p) throws IOException, InterruptedException {
        // The packager's runtime view IS its entry jars — coordinate-named lock artifacts plus
        // workspace sibling jars, the SAME set steps see via In.runtimeEntries(). Packaging from
        // the lock alone drops sibling module jars and ships an artifact that cannot start.
        List<PluginBuild.ProdEntry> entries =
                PluginBuild.productionEntries(p.moduleDir(), p.cache(), p.lockFile(), p.project());
        List<Path> entryJars = new ArrayList<>(entries.size());
        for (PluginBuild.ProdEntry e : entries) {
            if (e.jar() != null) entryJars.add(e.jar());
        }
        // Packagers get the packager-dependency artifacts AND the step-dependency tools (the same
        // artifacts commands receive — an AAB packager forks bundletool exactly like a step forks
        // aapt2). A packager-dependency wins a name collision.
        Map<String, String> sdkPins = PluginBuild.sdkPins(p.lockFile());
        Map<String, Path> extras =
                new LinkedHashMap<>(PluginBuild.fetchStepDependencies(p.project(), p.moduleDir(), p.cas(), sdkPins));
        extras.putAll(PluginBuild.fetchPackagerDependencies(p.project(), p.moduleDir(), p.cas()));

        ProjectFacts facts =
                PluginBuild.facts(p.project(), PlannerPlugin.resolvedMain(p.project(), p.moduleDir(), p.classes()));
        List<String> tokens = new ArrayList<>(PlannerPlugin.declaredInputTokens(
                p.decls().packager().inputs(),
                new PlannerPlugin.InputSources(
                        p.classes(), entryJars, entries, p.active().config(), p.layout(), p.moduleDir())));
        tokens.addAll(PlannerPlugin.toolTokens(
                PluginContributions.stepDependencies(p.project(), p.moduleDir()), extras, sdkPins));
        if (!p.secrets().isEmpty()) {
            // A changed signing credential re-signs (the signature is part of the artifact); the
            // key carries only a digest — a secret value never appears anywhere readable.
            StringBuilder sb = new StringBuilder();
            for (var e : new TreeMap<>(p.secrets()).entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            tokens.add("secrets:" + Hashing.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8)));
        }
        // [manifest] attributes ride inside the facts token — they reach the packager, so they key it.
        tokens.add("facts:" + facts.token());
        // The JDK the packager runs against reaches the body as spec.javaHome and decides the
        // bytecode it may rewrite or the launcher it embeds, so it keys the output.
        tokens.add("jdk:" + ActionKey.jdkToken(p.javaHome()));
        // Packager identity (e.g. shrink vs boot) so CLI packaging overrides cannot cache-collide.
        tokens.add("packaging:" + p.decls().packager().name());
        // The packager's CODE is an input, same as plugin steps (see PlannerPlugin.pluginTask).
        tokens.add("worker:" + ClasspathFingerprint.entry(PluginBuild.workerJarFor(p.active(), p.cache())));
        // The minified packager folds `jk train` observations into its keep rules out-of-band
        // (same path derivation as MinifiedJarPackager.produce). Absence and every content state
        // must be distinct keys — otherwise a post-train rebuild restores the pre-train jar as
        // "up-to-date" and training never reaches the shipped artifact.
        if ("minified-jar".equals(p.decls().packager().name())) {
            Path trainSurface = p.artifact()
                    .getParent()
                    .resolve(TrainLayout.ROOT)
                    .resolve("merged")
                    .resolve(TrainLayout.SURFACE_JSON);
            tokens.add("train:"
                    + (Files.isRegularFile(trainSurface) ? ClasspathFingerprint.entry(trainSurface) : "absent"));
        }
        String taskId = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_JAR, p.artifact());
        String pkgKey = ActionKey.forArtifact(taskId, BuildIdentity.cacheKeyVersion(), tokens);
        return new PackagerKey(new Keyed(taskId, tokens, pkgKey), facts, entries, extras);
    }

    /**
     * {@code classes:} fingerprint for package/assembly keys — live tree when present, else the
     * record of the CURRENT compile key ({@code compileMainKey}) merged with current resource
     * roots (post-{@code jk clean} restore path). Never {@code lastFor}: after an edit → build →
     * revert → clean, the last record names the other edit's outputs while the live build would
     * restore the reverted ones — reconstruction must match the live restore or the forecast
     * flips to false CACHED/RUN.
     *
     * <p>When the live classes tree is present but main/extra resources have drifted, projects the
     * post-{@code copy-resources} tree (class files + source resource roots) so package CACHED/RUN
     * matches the live package step after the copy — not the stale pre-copy classes dir.
     */
    static String classesTokenForPackage(
            Path dir,
            boolean compact,
            BuildLayout layout,
            JkBuild project,
            ActionCache actionCache,
            @Nullable String compileMainKey,
            @Nullable Boolean knownResourceDrift)
            throws IOException {
        Path classesDir = layout.classesDir();
        if (TaskForecaster.classesDirHasContent(classesDir)) {
            // Reuse the forecast's single drift detection when it ran — a re-walk here
            // could disagree with it and project the token from a different tree state.
            boolean drifted = knownResourceDrift != null
                    ? knownResourceDrift
                    : TaskForecaster.mainResourcesOutOfSync(dir, compact, classesDir);
            if (drifted) {
                return classesTokenProjectedAfterResourceCopy(dir, compact, layout, project);
            }
            return ClasspathFingerprint.entry(classesDir);
        }
        Map<String, String> compileOut = compileMainKey == null
                ? Map.of()
                : actionCache
                        .lookup(compileMainKey)
                        .map(ActionCache.ActionRecord::outputs)
                        .orElse(Map.of());
        List<Path> resRoots = packageResourceRoots(dir, compact);
        if (compileOut.isEmpty() && resRoots.isEmpty()) {
            return ClasspathFingerprint.entry(classesDir); // missing:… — package key will miss
        }
        return ClasspathFingerprint.entryFromCompileAndResources(compileOut, resRoots);
    }

    /**
     * Projected {@code classes:} token after {@code copy-resources} would merge source resource
     * roots over the current classes tree. Matches the live package-jar fingerprint once the
     * copy step has run — used when main resources are out of sync so package CACHED/RUN does not
     * lie about a pre-copy tree.
     */
    static String classesTokenProjectedAfterResourceCopy(Path dir, boolean compact, BuildLayout layout, JkBuild project)
            throws IOException {
        return ClasspathFingerprint.entryProjectedAfterResourceCopy(
                layout.classesDir(), packageResourceRoots(dir, compact));
    }

    /** Resource roots that {@code copy-resources} merges into {@code classes/} (main + plugin). */
    static List<Path> packageResourceRoots(Path dir, boolean compact) {
        List<Path> resDirs = new ArrayList<>();
        Path resMain = ModuleLayout.mainResourcesDir(dir, compact);
        if (Files.isDirectory(resMain)) resDirs.add(resMain);
        for (var root : ModuleLayoutPlugins.pluginContributedRoots(dir)) {
            if (!root.resource()) continue;
            Path r = dir.resolve(root.relative());
            if (Files.isDirectory(r)) resDirs.add(r);
        }
        return resDirs;
    }

    /**
     * The {@code package-jar} step for a module whose packaging a plugin owns: the same body the
     * build keys with, so the forecast names the artifact the packager will write and the key it
     * will compute. Reproducing that key needs the packager's tools and worker jar on disk; when
     * anything is missing the answer is RUN with the reason, which is pessimistic and therefore
     * safe — the failure mode this replaces was a confident forecast of a different step.
     *
     * <p>Two inputs the forecast cannot know are deliberately absent, and both fail that way:
     * CLI-supplied signing secrets ({@code jk assemble --secret}), which the build folds into
     * {@code secrets:}, and a transform step's re-pointed classes dir. Either makes the key miss
     * and the module forecast RUN — never a hit against the wrong bytes.
     */
    static TaskForecast.Task pluginPackagerStep(
            JkBuild project,
            Path dir,
            BuildLayout layout,
            Path cache,
            Path lockFile,
            Cas cas,
            Path javaHome,
            PackagingKeys.@Nullable Owner plugin,
            ActionCache actionCache) {
        Path artifact = PluginBuild.mainArtifactPath(layout, plugin.active());
        String packager = plugin.decls().packager().name();
        try {
            var keyed = PackagingKeys.pluginPackager(new PackagingKeys.Packager(
                            project,
                            dir,
                            cache,
                            lockFile,
                            cas,
                            layout,
                            layout.classesDir(),
                            artifact,
                            javaHome,
                            plugin.active(),
                            plugin.decls(),
                            Map.of())) // secrets: absent — see the javadoc
                    .keyed();
            if (TaskForecaster.present(actionCache, keyed.key())) {
                return new TaskForecast.Task(
                        TaskNames.PACKAGE_JAR, TaskForecast.Status.CACHED, "", TaskForecaster.key8(keyed.key()));
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return new TaskForecast.Task(TaskNames.PACKAGE_JAR, TaskForecast.Status.RUN, "repackage · " + packager, null);
    }

    /** A module's active code plugin and its declarations; null {@code decls} means none declared. */
    public record Owner(PluginBuild.Active active, PluginBuild.Declarations decls) {}

    /** The active code plugin with its declarations, or null when the module has none. */
    static @Nullable Owner pluginFor(JkBuild project, BuildLayout layout, Path cache)
            throws IOException, InterruptedException {
        var active = PluginBuild.activeCodePlugin(project, layout.moduleRoot());
        if (active.isEmpty()) return null;
        return new Owner(
                active.get(),
                PluginBuild.declarations(active.get(), project, layout.moduleRoot(), cache, layout.moduleTargetDir()));
    }

    /**
     * Whether the plugin packs this module's main artifact instead of jk's {@code JarPackager}.
     * This is {@code PlannerPackage.packageJarStep}'s own dispatch, spelled once so the forecast
     * asks exactly the question the build answers — the gap survived because the
     * forecast had no way to take the branch and the guard's exemption said so accurately.
     */
    static boolean ownsPackaging(@Nullable Owner owner) {
        return owner != null
                && owner.decls() != null
                && owner.decls().packager() != null
                && PlannerPackage.ownsMainArtifact(owner.active());
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
