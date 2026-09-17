// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.ModuleLayoutPlugins;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.SourcesMode;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginModule;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.surface.TrainLayout;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.FileHashMemo;
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
import java.util.Objects;
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
            @Nullable Boolean knownResourceDrift,
            @Nullable String projectedClassesTok)
            throws IOException {
        // The walk's own projection of a wiped tree wins: it merges every compiler's record, where
        // the reconstruction below reads only javac's.
        String classesTok = projectedClassesTok != null
                ? projectedClassesTok
                : classesTokenForPackage(
                        dir,
                        CompileSupport.isSimpleLayout(project.project(), dir),
                        layout,
                        project,
                        actionCache,
                        compileMainKey,
                        knownResourceDrift);
        // Same jar set as PlannerTails.assemblyStep (ModuleRuntimeClasspath), the wiped sibling
        // jars the walk pinned included.
        List<Path> depJars = PlannerSupport.assemblyDependencyJars(dir, project, lockFile, cache, restoredJarShas);
        PluginDeclarations pkgDecls;
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
        boolean hit = TaskForecaster.present(actionCache, keyed.key());
        if (Perf.enabled() && !hit) {
            // The tokens beside the record's own INPUT line are what tell a wiped jar nobody
            // pinned from a token whose recipe drifted; the per-jar parts name the entry.
            List<String> parts = new ArrayList<>(depJars.size());
            for (Path jar : depJars)
                parts.add(jar.getFileName() + "=" + fingerprintJarOrCached(jar, actionCache, restoredJarShas));
            Perf.note("forecast-assembly " + dir, "key", keyed.key(), "tokens", keyed.tokens(), "deps", parts);
        }
        return hit;
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

    // ---- native-image (executable) ------------------------------------------------------------

    /** The token prefixes {@code PlannerNative.imageKey} spells, in the order it lists them. */
    private static final List<String> NATIVE_TOKEN_PREFIXES =
            List.of("cp:", "args:", "main:", "shared:", "out:", "graal:", "framework:", "train:");

    /**
     * Whether the native-image action cache holds the executable this build would restore, judged
     * read-only after {@code jk clean} has taken the binary and the jar. The step derives its key
     * while it runs — the main class from the jar, the args from the plugins and the metadata
     * repository, the framework sources from a packager's augment — so the forecast replays the
     * step's last record instead: every input it can see is recomputed and every input it cannot
     * see is taken from the record, and the key those tokens make is looked up as the step would
     * look up its own.
     *
     * <p>Recomputed: the classpath token from the jar the package forecast pinned and the runtime
     * closure the lock resolves ({@link #fingerprintJarOrCached}), the output name and the trained
     * reachability tree. Checked against the record: the {@code [native]} args and plugin args the
     * manifest names (the metadata-repository prefix the step prepends is the lock's and rides
     * along), and a configured main class. Taken from the record: the GraalVM release token, which
     * no read-only path resolves, and a scanned main class, which an unchanged classes tree scans
     * the same. Anything the replay cannot vouch for — a shared library, a packager that builds
     * its own image, a framework-sources tree, a classes-run packager's exploded classpath, a jar
     * with no pinned content — answers false: a full wall, never a false restore.
     */
    static boolean nativeActionCached(
            Path dir,
            JkBuild project,
            BuildLayout layout,
            Path lockFile,
            ActionCache actionCache,
            Path cache,
            Map<Path, String> restoredJarShas)
            throws IOException {
        if (PluginBuild.shape(project, dir)
                .map(PluginDescriptor.Packaging::classesRun)
                .orElse(false)) return false;
        if (PlannerNative.packagerDeclaresNativeSources(project, dir)) return false;
        Path out = layout.nativeBinary();
        String task = ActionKey.qualifiedTaskId(TaskNames.NATIVE_IMAGE, out);
        var record = actionCache.lastFor(task);
        if (record.isEmpty()) return false;
        Map<String, String> stored = nativeTokens(record.get().inputs().get("inputs"));
        if (stored.size() != NATIVE_TOKEN_PREFIXES.size()) return false;
        // An executable only: a shared library's record names no binary this replay can restore.
        if (EnvValues.parseBool(stored.get("shared:")).orElse(true)) return false;
        if (!"".equals(stored.get("framework:"))) return false;
        if (!Objects.equals(stored.get("out:"), out.getFileName().toString())) return false;

        JkBuild.NativeConfig nativeCfg = project.nativeConfigOpt().orElse(null);
        String configuredMain =
                nativeCfg != null && nativeCfg.mainClass() != null ? nativeCfg.mainClass() : project.mainClass();
        if (configuredMain != null && !configuredMain.isBlank() && !configuredMain.equals(stored.get("main:"))) {
            return false;
        }
        List<String> declaredArgs = new ArrayList<>(PluginContributions.nativeArgs(project, dir));
        if (nativeCfg != null) declaredArgs.addAll(nativeCfg.args());
        if (!declaredArgsMatch(Objects.requireNonNull(stored.get("args:")), declaredArgs)) return false;

        List<Path> classpath = new ArrayList<>();
        classpath.add(layout.mainJar());
        for (Path jar : PlannerSupport.assemblyDependencyJars(dir, project, lockFile, cache, restoredJarShas)) {
            if (!classpath.contains(jar)) classpath.add(jar);
        }
        Path trainReach = PlannerNative.trainReachabilityDir(layout);
        List<String> tokens = List.of(
                "cp:" + fingerprintDepJars(classpath, actionCache, restoredJarShas),
                "args:" + stored.get("args:"),
                "main:" + stored.get("main:"),
                "shared:false",
                "out:" + out.getFileName(),
                "graal:" + stored.get("graal:"),
                "framework:",
                "train:" + (trainReach == null ? "" : ClasspathFingerprint.entry(trainReach)));
        return TaskForecaster.present(
                actionCache, ActionKey.forArtifact(task, BuildIdentity.cacheKeyVersion(), tokens));
    }

    /**
     * The record's {@code inputs} line as the tokens it was joined from, keyed by prefix. A token's
     * value may itself hold the joiner (an arg with a {@code ;}), so a fragment that opens with no
     * known prefix continues the token before it.
     */
    static Map<String, String> nativeTokens(@Nullable String joined) {
        Map<String, String> tokens = new LinkedHashMap<>();
        if (joined == null || joined.isBlank()) return tokens;
        String current = null;
        for (String fragment : joined.split(";", -1)) {
            String prefix = null;
            for (String p : NATIVE_TOKEN_PREFIXES) {
                if (fragment.startsWith(p)) {
                    prefix = p;
                    break;
                }
            }
            if (prefix != null) {
                current = prefix;
                tokens.put(prefix, fragment.substring(prefix.length()));
            } else if (current != null) {
                tokens.put(current, tokens.get(current) + ";" + fragment);
            }
        }
        return tokens;
    }

    /**
     * True when the recorded {@code args:} line is the manifest's declared args, allowing for the
     * reachability-metadata prefix the step prepends when the lock pins a metadata repository:
     * {@code -H:+UnlockExperimentalVMOptions -H:ConfigurationFileDirectories=… -H:-UnlockExperimentalVMOptions}.
     */
    static boolean declaredArgsMatch(String storedArgs, List<String> declared) {
        String tail = String.join(" ", declared);
        if (storedArgs.equals(tail)) return true;
        String unlock = "-H:+UnlockExperimentalVMOptions -H:ConfigurationFileDirectories=";
        if (!storedArgs.startsWith(unlock)) return false;
        int relock = storedArgs.indexOf(" -H:-UnlockExperimentalVMOptions", unlock.length());
        if (relock < 0) return false;
        String rest = storedArgs.substring(relock + " -H:-UnlockExperimentalVMOptions".length());
        return rest.equals(tail.isEmpty() ? "" : " " + tail);
    }

    // ---- plugin packager (spring-boot / grails / quarkus / minified / android) ---------------

    /** Everything the plugin-packager key and the packager's spec are both derived from. */
    public record Packager(
            JkBuild project,
            Path moduleDir,
            Path cache,
            Path lockFile,
            Cas cas,
            PluginBuild.StepTools tools,
            BuildLayout layout,
            Path classes,
            Path artifact,
            Path javaHome,
            @Nullable ActivePlugin active,
            PluginDeclarations decls,
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
        // the lock alone drops sibling module jars and ships an artifact that cannot start; a lock
        // row the store lacks fails here by name rather than shipping an artifact without it.
        List<PluginBuild.ProdEntry> entries =
                PluginBuild.productionEntries(p.moduleDir(), p.cas(), p.lockFile(), p.project());
        List<Path> entryJars = new ArrayList<>(entries.size());
        for (PluginBuild.ProdEntry e : entries) {
            if (e.jar() != null) entryJars.add(e.jar());
        }
        // Packagers get the packager-dependency artifacts AND the step-dependency tools that reach
        // them — the unscoped ones plus those whose for-step names this packager (an AAB packager
        // forks bundletool exactly like a step forks aapt2). A packager-dependency wins a name
        // collision.
        PluginDeclarations.PackagerDecl packager =
                Objects.requireNonNull(p.decls().packager(), "packager");
        Map<String, String> sdkPins = PluginBuild.sdkPins(p.lockFile());
        List<PluginContributions.StepDep> tools =
                p.tools().forConsumer(p.project(), p.moduleDir(), p.lockFile(), packager.name());
        Map<String, Path> extras = new LinkedHashMap<>(p.tools().fetch(tools, p.project(), p.cas(), sdkPins));
        extras.putAll(PluginBuild.fetchPackagerDependencies(p.project(), p.moduleDir(), p.cas(), p.lockFile()));

        ProjectFacts facts =
                PluginBuild.facts(p.project(), PlannerPlugin.resolvedMain(p.project(), p.moduleDir(), p.classes()));
        ActivePlugin active = Objects.requireNonNull(p.active(), "active");
        // A packager keys on the runtime view; the compile view is resolved only when one declares it.
        List<Path> compileClasspath =
                packager.inputs().contains(In.compileClasspath().wireName())
                        ? PluginBuild.compileClasspath(p.moduleDir(), p.cas(), p.lockFile(), p.project())
                        : entryJars;
        List<String> tokens = new ArrayList<>(PlannerPlugin.declaredInputTokens(
                packager.inputs(),
                new PlannerPlugin.InputSources(
                        p.classes(),
                        entryJars,
                        compileClasspath,
                        entries,
                        active.config(),
                        p.layout(),
                        p.moduleDir(),
                        SiblingFiles.forInputs(packager.inputs(), p.moduleDir(), p.project(), active.manifest()),
                        PluginRepositories.NONE)));
        tokens.addAll(PlannerPlugin.toolTokens(tools, extras, sdkPins));
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
        if ("minified-jar".equals(packager.name())) {
            Path trainSurface = Objects.requireNonNull(p.artifact().getParent(), "artifact dir")
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
     *
     * <p>A tree that lacks an output the compile record owns takes the record path as an empty
     * tree does: the live build restores the whole tree before it packages, so the token of the
     * partial tree names a jar that build never produces.
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
        if (TaskForecaster.classesDirHasContent(classesDir)
                && ModuleOutputs.compileOutputsOnDisk(actionCache, compileMainKey, classesDir)) {
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
        Map<String, String> copied = copiedPluginManifest(dir);
        if (compileOut.isEmpty() && resRoots.isEmpty() && copied.isEmpty()) {
            return ClasspathFingerprint.entry(classesDir); // missing:… — package key will miss
        }
        return ClasspathFingerprint.entryFromCompileAndResources(compileOut, resRoots, copied);
    }

    /**
     * The one file {@code copy-resources} places in the classes tree from outside the resource
     * roots: a plugin worker's module-root {@code jk-plugin.toml}, copied to the tree's root after
     * the roots are mirrored. Tree-relative path to content sha; empty for every other module.
     */
    static Map<String, String> copiedPluginManifest(Path dir) throws IOException {
        Path manifest = dir.resolve(ManifestPaths.PLUGIN_MANIFEST);
        if (!Files.isRegularFile(manifest)) return Map.of();
        return Map.of(ManifestPaths.PLUGIN_MANIFEST, FileHashMemo.contentHash(manifest));
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
            ActivePlugins.@Nullable Declared plugin,
            ActionCache actionCache) {
        ActivePlugins.Declared owner = Objects.requireNonNull(plugin, "plugin owner");
        ActivePlugin packagerPlugin = Objects.requireNonNull(owner.packager(), "packager plugin");
        Path artifact = PluginBuild.mainArtifactPath(layout, packagerPlugin);
        String packager =
                Objects.requireNonNull(owner.decls().packager(), "packager").name();
        try {
            var keyed = PackagingKeys.pluginPackager(new PackagingKeys.Packager(
                            project,
                            dir,
                            cache,
                            lockFile,
                            cas,
                            new PluginBuild.StepTools(),
                            layout,
                            layout.classesDir(),
                            artifact,
                            javaHome,
                            packagerPlugin,
                            owner.decls(),
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

    /** The module's active code plugins with their merged declarations, or null when it has none. */
    static ActivePlugins.@Nullable Declared pluginFor(JkBuild project, BuildLayout layout, Path cache)
            throws IOException, InterruptedException {
        return ActivePlugins.declared(project, layout.moduleRoot(), cache, layout.moduleTargetDir());
    }

    /**
     * Whether a plugin packs this module's main artifact instead of jk's {@code JarPackager}.
     * This is {@code PlannerPackage.packageJarStep}'s own dispatch, spelled once so the forecast
     * asks exactly the question the build answers.
     */
    static boolean ownsPackaging(ActivePlugins.@Nullable Declared plugins) {
        return plugins != null && plugins.packager() != null && plugins.decls().packager() != null;
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    // ---- library artefacts: package-sources + package-javadoc -----------------------------------

    /**
     * Whether {@code jk package} writes the sources and javadoc jars beside the module jar. A
     * library is a module with main sources and no {@code [application]} table — the model's own
     * definition of one; {@code sources = "always"} forces the pair for any module, application
     * included. A coordinator root packages nothing.
     */
    public static boolean libraryArtifacts(JkBuild project, Path moduleDir) {
        if (project.project().sourcesMode() == SourcesMode.ALWAYS) return true;
        if (project.isApplication()) return false;
        return !CompileSupport.coordinatorOnly(project, moduleDir) && CompileSupport.hasSources(moduleDir);
    }

    /**
     * The javadoc jar's key: the documented sources' content (Java, and Kotlin when Dokka runs),
     * the classpath's ABI (through {@code cp}, so the forecast can answer for a tree it has not
     * restored), the tool — javadoc's options, or the Dokka release and format as {@code dokka} —
     * and the JDK. One body for the step and its forecast; a module with no source of either
     * language keys a constant.
     */
    public static Keyed javadoc(
            Path javadocJar,
            Path moduleDir,
            List<Path> javaSources,
            List<Path> kotlinSources,
            List<Path> classpath,
            ActionKey.EntryToken cp,
            List<String> options,
            @Nullable Path javaHome,
            @Nullable String dokka)
            throws IOException {
        // A module with nothing to document gets the README-only jar, a constant: nothing about the
        // classpath, the tool or the JDK reaches it, so none of them may reach its key.
        boolean readme = javaSources.isEmpty() && kotlinSources.isEmpty();
        List<Path> documented = new ArrayList<>(javaSources);
        documented.addAll(kotlinSources);
        String tool = dokka != null ? "dokka " + dokka : String.join(" ", options);
        List<String> tokens = List.of(
                "sources:" + (readme ? "" : sourcesToken(moduleDir, documented)),
                "classpath:" + (readme ? "" : classpathAbiToken(classpath, cp)),
                "options:" + (readme ? "" : tool),
                "jdk:" + (readme ? "" : ActionKey.jdkToken(javaHome)),
                "packaging:" + (readme ? "javadoc-readme" : "javadoc"));
        String taskId = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_JAVADOC, javadocJar);
        String jdKey = ActionKey.forArtifact(taskId, BuildIdentity.cacheKeyVersion(), tokens);
        return new Keyed(taskId, tokens, jdKey);
    }

    /** Content digest of {@code sources}, each spelled by its module-relative path. */
    static String sourcesToken(Path moduleDir, List<Path> sources) throws IOException {
        Path root = moduleDir.toAbsolutePath().normalize();
        TreeMap<String, String> byPath = new TreeMap<>();
        for (Path src : sources) {
            Path abs = src.toAbsolutePath().normalize();
            String rel = abs.startsWith(root) ? root.relativize(abs).toString().replace('\\', '/') : abs.toString();
            byPath.put(rel, FileHashMemo.contentHash(abs));
        }
        StringBuilder sb = new StringBuilder();
        for (var e : byPath.entrySet())
            sb.append(e.getKey()).append('\0').append(e.getValue()).append('\n');
        return Hashing.sha256Hex(sb.toString());
    }

    private static String classpathAbiToken(List<Path> classpath, ActionKey.EntryToken cp) throws IOException {
        List<String> parts = new ArrayList<>(classpath.size());
        for (Path p : classpath) parts.add(cp.of(p));
        parts.sort(Comparator.naturalOrder());
        return Hashing.sha256Hex(String.join("\n", parts));
    }
}
