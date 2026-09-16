// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;

import cc.jumpkick.androidsdk.AndroidSdk;
import cc.jumpkick.androidsdk.AndroidSdkInstaller;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.JkM2Config;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.BuiltInPluginJars;
import cc.jumpkick.guard.rules.GuardPacks;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.ToolchainLockStamp;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.lock.LockManifestDigest;
import cc.jumpkick.lock.LockNativePin;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileModules;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.JkBuild.NativeConfig;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.ToolchainSpec;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.repo.ArtifactLocator;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.MavenMetadataCache;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolve.ResolveProcessCacheControl;
import cc.jumpkick.resolve.ResolveProfile;
import cc.jumpkick.resolver.LanguageRuntimeInject;
import cc.jumpkick.resolver.LockOrchestrator;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.resolver.VersionSelectors;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.runtime.base.GuardSuiteLibrary;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.runtime.base.PluginDescriptorOps;
import cc.jumpkick.runtime.base.ReachabilityMetadata;
import cc.jumpkick.runtime.base.SdkComponents;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The one "resolve {@code jk.toml} → write {@code jk-lock.toml}" pipeline. {@code jk lock}, {@code
 * jk update}, {@code jk sync}'s first lock, the pre-build workspace freshen and the stale-manifest
 * auto-lock all run these stages in this order, so no entry point can write a lockfile that another
 * one would not have written:
 *
 * <ol>
 *   <li>{@link #manifestsSha()} — captured <em>before</em> resolving, so a manifest edited
 *       mid-resolution leaves a lock that reads as stale rather than stamping itself fresh.
 *   <li>{@link #resolve} — offline gate, git/path materialization, solve, git provenance stamp,
 *       Kotlin/Scala compiler pins, the {@code [native]} reachability-metadata pin, and
 *       {@code [jdk]} / {@code [graal]} toolchain pins.
 *   <li>{@link #pinPlugins} — {@code [[plugin]]} rows and the {@code jk-min} floor.
 *   <li>{@link #pinSdk} — {@code [[sdk]]} rows.
 *   <li>{@link #write} — {@code [[module]]} identity stamp, then the file.
 * </ol>
 *
 * <p>Everything a mode changes lives in {@link LockMode}; the stages themselves do not branch on
 * the caller.
 */
public final class LockPipeline {

    /**
     * Label/tick sink for one pass. Plan steps adapt their {@link TaskContext} through {@link
     * #of(TaskContext)}; the callers that run outside a plan pass {@link #SILENT}.
     */
    public interface Progress {

        /** Discards everything — for the direct (non-plan) callers. */
        Progress SILENT = new Progress() {};

        /** Current sub-task label. */
        default void label(String text) {}

        /** Advance the bar by {@code units}. */
        default void tick(int units) {}

        /** Durable free-form line for the view. */
        default void note(String line) {}
    }

    /** Route labels, ticks and notes to a running plan step. */
    public static Progress of(TaskContext ctx) {
        return new Progress() {
            @Override
            public void label(String text) {
                ctx.label(text);
            }

            @Override
            public void tick(int units) {
                ctx.progress(units);
            }

            @Override
            public void note(String line) {
                ctx.output(line);
            }
        };
    }

    /** What an offline pass does with the lock already on disk. */
    private enum OfflineReuse {
        /** {@code jk lock --offline}: the pinned graph verbatim, or a hard failure naming what is missing. */
        REQUIRED,
        /** A freshen: reuse the pinned graph when it is entirely local, else solve from the warm store. */
        PREFERRED,
        /** {@code jk update}: floating to latest is the whole point — never reuse. */
        NEVER
    }

    /**
     * The knobs one {@link LockMode} sets, derived by a single exhaustive switch so a mode added
     * later cannot silently inherit another's policy.
     */
    private record Policy(
            OfflineReuse offlineReuse,
            boolean keepPins,
            boolean pinGitRefsFromLock,
            boolean forceRevalidate,
            boolean sources,
            PlatformPolicy platform,
            /**
             * Whether {@code [jdk]} / {@code [graal]} keep the suggestion already on disk. A
             * suggestion records what built the lock, so re-locking on a different machine must
             * not quietly rewrite it — only {@code jk update}, whose job is moving forward, does.
             * Distinct from {@link #keepPins}, which is about reusing the resolved graph.
             */
            boolean keepToolchainSuggestion) {}

    private final Path lockDir;
    private final JkBuild effective;
    private final Path cache;
    private final @Nullable URI repoUrl;
    private final List<String> features;
    private final boolean withDefaults;
    private final String jkVersion;
    private final Policy policy;

    /** What the last {@link #resolve} checked its downloads against; {@code NONE} until it ran. */
    private volatile RepoGroup.TrustSummary trust = RepoGroup.TrustSummary.NONE;

    /**
     * @param lockDir the directory that owns the lockfile — a workspace root for a member, else the
     *     project itself (see {@link LockPlans#lockScope})
     * @param effective the workspace-merged manifest to resolve
     */
    public LockPipeline(
            Path lockDir,
            JkBuild effective,
            Path cache,
            @Nullable URI repoUrl,
            List<String> features,
            boolean withDefaults,
            LockMode mode) {
        this.lockDir = lockDir;
        this.effective = effective;
        this.cache = cache;
        this.repoUrl = repoUrl;
        this.features = List.copyOf(features);
        this.withDefaults = withDefaults;
        // There is exactly one right value; a parameter only let a caller pass the wrong one.
        this.jkVersion = JkVersion.VERSION;
        this.policy = policyFor(mode, effective);
    }

    private static Policy policyFor(LockMode mode, JkBuild effective) {
        return switch (mode) {
            case LockMode.Keep(boolean sources) ->
                new Policy(OfflineReuse.REQUIRED, true, true, false, sources, platformPolicy(effective, null), true);
            case LockMode.Latest(boolean sources) ->
                new Policy(OfflineReuse.REQUIRED, false, true, false, sources, platformPolicy(effective, null), true);
            case LockMode.Update(String platformOverride) ->
                new Policy(
                        OfflineReuse.NEVER,
                        false,
                        false,
                        true,
                        false,
                        platformPolicy(effective, platformOverride),
                        false);
            case LockMode.Freshen ignored ->
                new Policy(OfflineReuse.PREFERRED, true, true, false, false, platformPolicy(effective, null), true);
        };
    }

    /** CLI {@code --platform} override wins; else {@code [resolve] platform} (default enforced). */
    static PlatformPolicy platformPolicy(JkBuild project, @Nullable String override) {
        if (override != null && !override.isBlank()) {
            return PlatformPolicy.parse(override.trim());
        }
        return project != null && project.build() != null ? project.build().platformPolicy() : PlatformPolicy.ENFORCED;
    }

    /** The file this pipeline writes. */
    public Path lockFile() {
        return LockPaths.lockFile(lockDir);
    }

    /** The lock currently on disk for {@code lockDir}, or null when absent or unreadable. */
    public static @Nullable Lockfile readIfPresent(Path lockDir) {
        Path file = LockPaths.lockFile(lockDir);
        if (!Files.exists(file)) return null;
        try {
            return LockfileReader.read(file);
        } catch (Exception unreadable) {
            return null; // resolve fresh
        }
    }

    /** SHA-256 of every {@code jk.toml} feeding this lock. Capture this <em>before</em> resolving. */
    public String manifestsSha() throws IOException {
        return LockManifestDigest.compute(lockDir);
    }

    // ---- stage 2: resolve ----------------------------------------------------

    /**
     * Solve the dependency graph and stamp the tool pins onto it. {@code existing} is the lock this
     * pass will replace (null when there is none); it seeds the keep-pins preferences, immutable git
     * SHAs and the offline gate.
     */
    public Lockfile resolve(@Nullable Lockfile existing, ResolveObserver observer, Progress progress) throws Exception {
        Cas cas = JkStores.storeCas();
        // --force / Session force: drop process resolve memos before any POM/metadata work.
        if (SessionContext.current().config().forceOr(false)) {
            ResolveProcessCacheControl.clearAll();
        }
        Optional<Lockfile> reused = offlineReuse(existing, cas);
        if (reused.isPresent()) {
            progress.tick(reused.get().artifacts().size());
            return reused.get();
        }

        boolean profile = ResolveProfile.on();
        if (profile) ResolveProfile.reset();
        long prepT0 = profile ? System.nanoTime() : 0L;
        RepoGroup baseRepos = RepoGroupBuilder.buildFor(effective, repoUrl, cas, BuildEnv.forModule(lockDir));
        Map<String, String> lockedShas = policy.pinGitRefsFromLock() && existing != null
                ? GitSourceResolution.lockedImmutableShas(existing)
                : Map.of();
        // Git- and path-source deps: materialize each into a local file:// repo and rewrite them to
        // exact coordinate pins before the solver runs (git-source-deps.md).
        // One probe-chain registry serves both the JDK walk here and the toolchain
        // stamp below — no second filesystem scan per lock.
        JdkRegistry jdkRegistry = new JdkRegistry();
        Path javaHome = JavaHomes.resolveJavaHome(lockDir, jdkRegistry);
        GitSourceResolution.Prepared prep =
                GitSourceResolution.prepare(effective, baseRepos, cas, javaHome, jkVersion, lockedShas);
        PathSourceResolution.Prepared pathPrep =
                PathSourceResolution.prepare(prep.project(), prep.repos(), cas, lockDir, javaHome, jkVersion);
        if (profile) ResolveProfile.phasePrep(System.nanoTime() - prepT0);

        // Deliberately no Diagnostics.Palette here — the engine emits plain text and the client themes it.
        LockOrchestrator orchestrator = new LockOrchestrator(pathPrep.repos())
                .withProjectDir(lockDir)
                .withActivatedFeatures(pathPrep.activatedFeatures())
                .withJvmEnvironment(PluginContributions.jvmEnvironment(pathPrep.project(), lockDir))
                .withPlatformPolicy(policy.platform())
                .withUnmappedPolicy(pathPrep.project().build().unmappedPolicy());

        boolean keepPins = policy.keepPins() && existing != null;
        // Compiler pins first: the solve injects each language's stdlib pinned to its compiler.
        LanguageRuntimeInject.ToolVersions tools =
                resolveToolVersions(keepPins ? existing : null, pathPrep.repos(), progress);
        orchestrator.withToolVersions(tools);
        long resolveT0 = profile ? System.nanoTime() : 0L;
        Lockfile lock = solve(orchestrator, pathPrep.project(), keepPins ? existing : null, observer);
        if (profile) ResolveProfile.phaseResolve(System.nanoTime() - resolveT0);

        long postT0 = profile ? System.nanoTime() : 0L;
        lock = GitSourceResolution.stamp(lock, prep.gitInfoByKey());
        if (tools.kotlin() != null) lock = lock.withKotlin(tools.kotlin());
        if (tools.scala() != null) lock = lock.withScala(tools.scala());
        lock = withNativePin(lock, keepPins ? existing : null, pathPrep.repos(), progress);
        // graal() is non-null exactly when [native].graal is set or [native] turns native-image on,
        // which is what "the project asked for Graal" means.
        lock = Objects.requireNonNull(ToolchainLockStamp.apply(
                lock,
                policy.keepToolchainSuggestion() ? existing : null,
                javaHome,
                jdkRegistry,
                pathPrep.project().project().jdkSpec(),
                pathPrep.project()
                        .nativeConfigOpt()
                        .map(NativeConfig::graalSpec)
                        .orElse(ToolchainSpec.NONE),
                pathPrep.project().graal() != null));
        if (profile) {
            ResolveProfile.phasePost(System.nanoTime() - postT0);
            Log.info("jk: " + ResolveProfile.report());
        }
        trust = pathPrep.repos().trust();
        return lock;
    }

    /** See {@link RepoGroup#trust()}: the verified / unverified-allowed counts and plaintext repositories of the last resolve. */
    public RepoGroup.TrustSummary trust() {
        return trust;
    }

    private Lockfile solve(
            LockOrchestrator orchestrator, JkBuild project, @Nullable Lockfile pins, ResolveObserver observer)
            throws Exception {
        Lockfile lock = resolveGraph(orchestrator, project, pins, observer);
        return policy.sources() ? orchestrator.attachSources(lock) : lock;
    }

    private Lockfile resolveGraph(
            LockOrchestrator orchestrator, JkBuild project, @Nullable Lockfile pins, ResolveObserver observer)
            throws Exception {
        if (pins != null) {
            // Keep-pins pass: soft-prefer the existing pins; the metadata TTL is fine (pins win).
            return orchestrator.lockConservative(project, pins, jkVersion, features, withDefaults, observer);
        }
        if (policy.forceRevalidate()) {
            // Float-to-latest needs current indexes; revalidate past the TTL (conditional GET).
            return MavenMetadataCache.withForceRevalidate(
                    () -> orchestrator.lock(project, jkVersion, features, withDefaults, observer));
        }
        // Local maven-metadata within TTL first (default 24h) — do not force-revalidate every
        // lock (conditional GETs still 429 Central on large graphs). Fresh indexes: jk update,
        // or -F / --force (Session force → MavenMetadataCache).
        return orchestrator.lock(project, jkVersion, features, withDefaults, observer);
    }

    /**
     * The Kotlin and Scala compiler pins, resolved before the dependency solve so the injected
     * stdlibs can follow them exactly. A freshen carries the pin the lock already holds — bumping
     * the compiler is {@code jk lock}'s job — and otherwise every path resolves it, since a lock
     * written without it loses compiler provisioning.
     */
    private LanguageRuntimeInject.ToolVersions resolveToolVersions(
            @Nullable Lockfile pins, RepoGroup repos, Progress progress) {
        String kotlin = pins != null && pins.kotlin() != null ? pins.kotlin() : resolveKotlinVersion(effective, repos);
        if (kotlin != null) progress.label("resolved kotlin " + kotlin);
        String scala = pins != null && pins.scala() != null ? pins.scala() : resolveScalaVersion(effective, repos);
        if (scala != null) progress.label("resolved scala " + scala);
        return new LanguageRuntimeInject.ToolVersions(kotlin, scala);
    }

    /**
     * The {@code [native] metadata-repository} pin: the GraalVM reachability-metadata repository
     * release {@code jk native} will extract.
     *
     * <p>Locked for the same reason a dependency is. The repository decides which third-party
     * reflection, resource and proxy config the image keeps, so a floating selector resolved at
     * build time makes the binary depend on the day it was built. A freshen carries the pin the
     * lock already holds; bumping it is {@code jk lock}'s job.
     *
     * <p>Nothing under the lock owner building a native image means no pin and no fetch — the
     * repository zip is 3.3 MB on the wire and 27 MB unpacked, and a project that never runs
     * {@code native-image} must not pay for either. {@link LockNativePin} owns that question.
     */
    private Lockfile withNativePin(Lockfile lock, @Nullable Lockfile pins, RepoGroup repos, Progress progress) {
        if (pins != null && pins.nativeMetadata() != null) {
            return lock.withNativeMetadata(pins.nativeMetadata());
        }
        Optional<VersionSelector> declared;
        try {
            declared = LockNativePin.selector(lockDir);
        } catch (IOException e) {
            return lock; // unreadable manifest: the solve above already reported it
        }
        if (declared.isEmpty()) return lock;
        String version = highestMatch(declared.get(), repos, ReachabilityMetadata.coordinate("0"));
        if (version == null) {
            throw new IllegalStateException(
                    "[native] metadata-repository `" + declared.get().raw()
                            + "` matches no released version of org.graalvm.buildtools:graalvm-reachability-metadata");
        }
        progress.label("resolved reachability metadata " + version);
        return lock.withNativeMetadata(new Lockfile.NativeMetadata(version, metadataChecksum(repos, version)));
    }

    /**
     * SHA-256 of the repository zip, or null when it cannot be fetched. Null is not a failure: the
     * version alone already decides what a build extracts, and refusing to record the pin because
     * the bytes were momentarily out of reach would cost the next native build its metadata
     * entirely — a worse answer than a pin that is verified on the next fetch instead of this one.
     */
    private static @Nullable String metadataChecksum(RepoGroup repos, String version) {
        try {
            Coordinate coord = ReachabilityMetadata.coordinate(version);
            var found = repos.tryFetchArtifact(coord);
            if (found.isEmpty()) return null;
            return "sha256:" + Hashing.sha256Hex(found.get().fetched().cachePath());
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ---- stage 3: [[plugin]] rows -------------------------------------------

    /**
     * Pin every declared plugin jar by sha256 and raise {@code jk-min} to the highest floor the
     * project's configured table plugins demand. Throws when a declared plugin cannot be fetched or
     * its bytes disagree with the declared checksum.
     */
    public Lockfile pinPlugins(Lockfile lock, Progress progress) {
        progress.label("lock plugins");
        Cas cas = JkStores.storeCas();
        RepoGroup repos = RepoGroupBuilder.buildFor(effective, repoUrl, cas, BuildEnv.forModule(lockDir));
        List<Lockfile.PluginEntry> entries = new ArrayList<>();
        for (PluginDeclaration pd : effective.plugins()) {
            progress.label("lock " + pd.coordinate());
            entries.add(pinDeclared(pd, repos, progress));
            progress.tick(1);
        }
        Set<String> seen = new HashSet<>();
        for (Lockfile.PluginEntry e : entries) seen.add(e.coordinate() + ":" + e.version());
        String floor = lock.jkMin();
        for (BuiltInPluginJars.Located located : BuiltInPluginJars.locatedTablePlugins()) {
            PluginDescriptor d;
            try {
                d = BuiltInPluginJars.describe(located, false);
            } catch (RuntimeException unusable) {
                continue; // garbled, or another plugin's descriptor: engine install skipped it loudly
            }
            // Pin only plugins this project configures. Pinning every located plugin churned each
            // project's lock on every jk version bump and ping-ponged between developers on
            // different jk versions, for plugins the project never forks.
            if (effective.pluginConfig(d.id()).isEmpty()) continue;
            String coord = "cc.jumpkick:" + located.plugin().artifactId();
            if (!seen.add(coord + ":" + JkVersion.VERSION)) continue;
            try {
                entries.add(FirstPartyPins.running(located.plugin(), located.path()));
            } catch (IOException unreadable) {
                continue;
            }
            floor = PluginDescriptors.maxFloor(floor, PluginDescriptors.jkCompatFloor(d.jkCompat()));
        }
        // A guard suite anywhere in the workspace pins the provisioned jk-guards-junit like a built-in
        // plugin: same version, same store. A workspace that builds the library itself pins the module.
        if (hasGuardSuite()) {
            try {
                Lockfile.PluginEntry library = GuardSuiteLibrary.pin(lockDir, cas);
                if (library != null && seen.add(library.coordinate() + ":" + library.version())) {
                    entries.add(library);
                }
            } catch (IOException unreadable) {
                progress.note("note: " + GuardSuiteLibrary.COORDINATE + " could not be hashed; not pinned");
            }
        }
        // Rule packs pin like plugins: the root jk-guards.toml names them, the lock fixes the bytes
        // (or, for a first-party pack at a pre-release version, the version).
        List<String> packs;
        try {
            packs = GuardPacks.declared(lockDir);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot read " + GuardsPresence.RULES_FILE + " for [guards] extends: " + e.getMessage(), e);
        }
        for (String declared : packs) {
            GuardPacks.Coordinate c = GuardPacks.Coordinate.parse(declared);
            if (c == null) {
                throw new IllegalStateException("[guards] extends: `" + declared
                        + "` is not group:artifact:version — a rule pack is pinned exactly");
            }
            if (!seen.add(c.ga() + ":" + c.version())) continue;
            progress.label("lock pack " + c.gav());
            try {
                // A first-party pack is staged in the store like the worker jars; a third-party one
                // resolves through the project's repositories.
                String rel = c.group().replace('.', '/') + "/" + c.artifact() + "/" + c.version() + "/" + c.artifact()
                        + "-" + c.version() + ".jar";
                Path staged = null;
                for (RepoArtifactStore store : RepoArtifactStore.firstParty(cas.root())) {
                    Optional<Path> hit = store.locate(rel);
                    if (hit.isPresent()) {
                        staged = hit.get();
                        break;
                    }
                }
                Path jarPath;
                String sha;
                if (staged != null) {
                    jarPath = staged;
                    sha = Hashing.sha256Hex(staged);
                } else {
                    var fetched = repos.tryFetchArtifact(Coordinate.of(c.group(), c.artifact(), c.version()))
                            .orElseThrow(
                                    () -> new IllegalStateException("rule pack " + c.gav() + " not found in any repo"));
                    jarPath = fetched.fetched().cachePath();
                    sha = fetched.fetched().sha256();
                }
                // A first-party pack at a pre-release version pins by version alone, for the reason
                // a first-party plugin does: its bytes move with every rebuild, and a digest would
                // leave every committed consumer lock red after the next side-load.
                entries.add(
                        c.pinsByVersionOnly()
                                ? Lockfile.PluginEntry.versionOnly(c.ga(), c.version())
                                : new Lockfile.PluginEntry(c.ga(), c.version(), "sha256:" + sha));
                // The lock materializes the pack where the loader reads it, so a build after the lock
                // needs neither the repository nor the store to find its rules.
                GuardPacks.unpack(jarPath, GuardPacks.unpackedDir(lockDir, c), sha);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException("rule pack " + c.gav() + " — " + e.getMessage(), e);
            }
        }
        return lock.withPlugins(entries).withJkMin(floor);
    }

    private boolean hasGuardSuite() {
        List<Path> dirs = new ArrayList<>();
        dirs.add(lockDir);
        if (effective.workspace() != null)
            for (String m : effective.workspace().modules()) dirs.add(lockDir.resolve(m));
        for (Path d : dirs) {
            if (Files.isDirectory(d) && TestSuites.hasGuardSuite(d, ModuleLayout.isCompact(d))) return true;
        }
        return false;
    }

    private Lockfile.PluginEntry pinDeclared(PluginDeclaration pd, RepoGroup repos, Progress progress) {
        String hex;
        Path jarPath;
        try {
            if (pd.isPathPin()) {
                jarPath = resolvePluginPath(pd.path());
                if (!Files.isRegularFile(jarPath)) {
                    throw new IllegalStateException("plugins." + pd.alias() + " path `" + pd.path()
                            + "` is not a readable file (" + jarPath + ")");
                }
                hex = Hashing.sha256Hex(jarPath);
                requireSha(pd, hex, "file is", jarPath.toString());
            } else {
                Coordinate coord = Coordinate.of(pd.group(), pd.name(), pd.version());
                var fetched = repos.tryFetchArtifact(coord)
                        .orElseThrow(
                                () -> new IllegalStateException(pd.coordinateWithVersion() + " not found in any repo"));
                hex = fetched.fetched().sha256();
                requireSha(pd, hex, "resolved jar is", pd.coordinateWithVersion());
                jarPath = fetched.fetched().cachePath();
                // Sibling POM for worker classpath reconstruction.
                repos.tryFetchArtifact(new Coordinate(coord.group(), coord.artifact(), coord.version(), null, "pom"));
            }
        } catch (Exception e) {
            throw new IllegalStateException(pd.coordinate() + " — " + e.getMessage(), e);
        }
        try {
            PluginDescriptorOps.materialize(lockDir, hex, jarPath, pd.coordinate());
        } catch (IOException noDescriptor) {
            progress.note("note: " + pd.coordinate()
                    + " has no jk-plugin.toml — locked, but it will not own a jk.toml table");
        }
        return new Lockfile.PluginEntry(pd.coordinate(), pd.version(), "sha256:" + hex);
    }

    private static void requireSha(PluginDeclaration pd, String hex, String subject, String where) {
        if (!hex.equals(pd.sha256())) {
            throw new IllegalStateException("plugins." + pd.alias() + " sha256 mismatch: declared " + pd.sha256()
                    + " but " + subject + " " + hex + " (" + where + ")");
        }
    }

    /** Resolve a plugin path relative to the lock directory (or absolute). */
    private Path resolvePluginPath(@Nullable String raw) {
        Path p = Path.of(raw);
        return p.isAbsolute() ? p.normalize() : lockDir.resolve(p).normalize();
    }

    // ---- stage 4: [[sdk]] rows ----------------------------------------------

    /**
     * Pin every sdk-component a plugin contributes — the step lane and the command lane alike, an
     * offline command needs its pin exactly like a step does: the installed on-disk revision, else
     * the feed's stable revision when it is reachable. Unreachable components are left unpinned
     * rather than guessed.
     */
    public Lockfile pinSdk(Lockfile lock, Progress progress) {
        LinkedHashSet<String> components = new LinkedHashSet<>();
        try {
            Map<String, String> platformPins = lock.platformPins();
            for (var lane : List.of(
                    PluginContributions.stepDependencies(effective, lockDir, platformPins),
                    PluginContributions.commandDependencies(effective, lockDir, platformPins))) {
                for (var sd : lane) {
                    if (sd.sdkComponent() != null && !"root".equals(sd.sdkComponent())) {
                        components.add(sd.sdkComponent());
                    }
                }
            }
        } catch (RuntimeException e) {
            // no plugin tables / no contributions — nothing to pin
            Log.debug("pinSdk: no plugin tables / no contributions", e);
        }
        if (components.isEmpty()) return lock;
        progress.label("pin sdk components");
        List<Lockfile.SdkEntry> entries = new ArrayList<>();
        for (String component : components) {
            String revision = SdkComponents.installedRevision(component);
            if (revision == null) revision = feedRevision(component);
            if (revision != null) entries.add(new Lockfile.SdkEntry(component, revision));
        }
        return lock.withSdk(entries);
    }

    private static @Nullable String feedRevision(String component) {
        try {
            var feedComponent =
                    new AndroidSdkInstaller(AndroidSdk.resolve()).feed().find(component);
            return feedComponent != null ? feedComponent.revision() : null;
        } catch (Exception offline) {
            return null; // feed unreachable — leave unpinned rather than guess
        }
    }

    // ---- stage 5: write ------------------------------------------------------

    /** Freeze resolved first-party project identity, then write. Returns the lock as written. */
    public Lockfile write(Lockfile lock, String manifestsSha) throws IOException {
        Lockfile stamped = LockfileModules.stamp(lock, lockDir);
        LockfileWriter.write(stamped, lockFile(), manifestsSha);
        return stamped;
    }

    /** Every stage, for the callers that do not split the pipeline across plan steps. */
    public Lockfile run(@Nullable Lockfile existing, ResolveObserver observer, Progress progress) throws Exception {
        String manifestsSha = manifestsSha();
        Lockfile lock = resolve(existing, observer, progress);
        lock = pinPlugins(lock, progress);
        lock = pinSdk(lock, progress);
        return write(lock, manifestsSha);
    }

    // ---- offline gate --------------------------------------------------------

    private Optional<Lockfile> offlineReuse(@Nullable Lockfile existing, Cas cas) {
        if (existing == null || !SessionContext.current().offline()) return Optional.empty();
        return switch (policy.offlineReuse()) {
            case NEVER -> Optional.empty();
            case REQUIRED -> {
                requireOfflineSatisfiable(existing, cas);
                yield Optional.of(existing);
            }
            case PREFERRED -> {
                try {
                    requireOfflineSatisfiable(existing, cas);
                    yield Optional.of(existing);
                } catch (RuntimeException notFullyLocal) {
                    yield Optional.empty(); // solve from the warm store instead
                }
            }
        };
    }

    /** Throw if {@code lock} can't be honored entirely from the local store while offline. */
    private void requireOfflineSatisfiable(Lockfile lock, Cas cas) {
        Set<String> locked = new HashSet<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            // Index package key and GA — declared deps use GA; lock rows use g:a:type:classifier.
            locked.add(pkg.name());
            locked.add(pkg.packageKey());
            try {
                if (PackageId.isMavenPackageKey(pkg.name())) {
                    locked.add(PackageId.parse(pkg.name()).ga());
                }
            } catch (RuntimeException e) {
                // non-Maven lock name
                Log.debug("requireOfflineSatisfiable: non-Maven lock name", e);
            }
        }
        for (var entry : effective.dependencies().byScope().entrySet()) {
            if (entry.getKey() == Scope.PLATFORM) continue;
            for (var dep : entry.getValue()) {
                if (!locked.contains(dep.module())) {
                    throw new IllegalStateException("offline: " + dep.module()
                            + " is declared in jk.toml but not in jk-lock.toml; run `jk lock` online first");
                }
            }
        }
        ArtifactLocator locator = offlineLocator(lock, cas);
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            String checksum = pkg.checksum();
            if (checksum == null) {
                // Nothing to materialize for POM-only rows. Still say so — a checksum-less jar row
                // must not be silently treated as present.
                Log.info("jk: note: lock row " + pkg.name() + "@" + pkg.version()
                        + " has no checksum — offline check skipped it (POM-only alias, or incomplete lock)");
                continue;
            }
            String hex = checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum;
            if (locator.locate(pkg).isEmpty()) {
                throw new IllegalStateException("offline: " + pkg.name() + ":" + pkg.version()
                        + " is locked but its artifact isn't cached"
                        + mirrorMismatchHint(cas, pkg, hex)
                        + "; run `jk sync` online first");
            }
        }
    }

    /**
     * The locator the compile classpath itself will use: the Maven local repository when
     * integration is on and no locked module opted out, then {@code repos/<name>/}. The offline
     * gate has to answer the question the build will ask, and since Maven layout became the only
     * dependency store a dependency jar is never a CAS blob.
     */
    private static ArtifactLocator offlineLocator(Lockfile lock, Cas cas) {
        boolean m2 = JkM2Config.resolve().integration()
                && lock.modules().stream().noneMatch(m -> Boolean.FALSE.equals(m.m2integration()));
        return new ArtifactLocator(cas.root(), m2 ? M2Dirs.localRepository() : null, m2);
    }

    /**
     * Extra diagnosis for the offline miss above: when the mirror <em>does</em> hold this coordinate
     * but under different bytes than the lock pins, "isn't cached" is misleading — the artifact is
     * right there, it simply is not the one the lockfile named. Say so and name the escape hatch,
     * since `jk sync` alone will not resolve a first-write-wins mirror entry.
     *
     * @return a clause to append to the message, or "" when the mirror has nothing to say
     */
    private static String mirrorMismatchHint(Cas cas, Lockfile.Artifact pkg, String lockedHex) {
        try {
            if (pkg.name().indexOf(':') < 0) return "";
            String repoName = RepoArtifactResolver.repoName(pkg.source());
            if (repoName == null || !RepoArtifactResolver.isNamedRemote(repoName)) return "";
            String m2Path = MavenLayout.artifactPath(pkg.coordinate());
            String stored = RepoArtifactStore.forSource(cas.root(), pkg.source())
                    .storedSha256(m2Path)
                    .orElse(null);
            if (stored == null || stored.equalsIgnoreCase(lockedHex)) return "";
            return " (the " + repoName + " mirror holds different bytes for it — sha256 " + shortSha(stored)
                    + " vs the locked " + shortSha(lockedHex)
                    + "; `jk repo refresh " + pkg.coordinate() + "` once online drops the stale entry)";
        } catch (RuntimeException e) {
            return ""; // diagnosis is a nicety — never let it replace the real error
        }
    }

    private static String shortSha(String hex) {
        return hex == null || hex.length() <= 12 ? String.valueOf(hex) : hex.substring(0, 12);
    }

    // ---- tool pins -----------------------------------------------------------

    /**
     * Resolve the project's {@code kotlin} version selector to a concrete Kotlin compiler release.
     * Returns null for a Java project or when resolution can't complete.
     */
    static @Nullable String resolveKotlinVersion(JkBuild effective, RepoGroup repos) {
        VersionSelector kotlin = effective.project().kotlin();
        if (kotlin == null) return null;
        return highestMatch(kotlin, repos, Coordinate.of("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "any"));
    }

    /**
     * Resolve the project's {@code scala} version selector to a concrete Scala 3 compiler release.
     * Returns null for a non-Scala project or when resolution can't complete.
     */
    public static @Nullable String resolveScalaVersion(JkBuild effective, @Nullable RepoGroup repos) {
        VersionSelector scala = effective.project().scala();
        if (scala == null) return null;
        return highestMatch(scala, repos, Coordinate.of("org.scala-lang", "scala3-compiler_3", "any"));
    }

    private static @Nullable String highestMatch(
            VersionSelector selector, @Nullable RepoGroup repos, Coordinate coord) {
        if (selector instanceof VersionSelector.Exact exact) {
            return exact.version();
        }
        requireNonNull(repos, () -> "a floating selector needs the catalog: " + selector);
        List<String> available;
        try {
            available = repos.availableVersions(coord);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return pickVersion(VersionSelectors.toVersionSet(selector), available);
    }

    /** Highest stable match in {@code available}; falls back to any matching version. */
    public static @Nullable String pickVersion(VersionSet set, List<String> available) {
        return available.stream()
                .filter(set::contains)
                .filter(Versions::isStable)
                .max(Versions::compare)
                .or(() -> available.stream().filter(set::contains).max(Versions::compare))
                .orElse(null);
    }
}
