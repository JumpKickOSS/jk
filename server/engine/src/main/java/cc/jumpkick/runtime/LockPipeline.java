// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.androidsdk.AndroidSdk;
import cc.jumpkick.androidsdk.AndroidSdkInstaller;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.JkM2Config;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.BuiltInPluginJars;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.ToolchainLockStamp;
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
import cc.jumpkick.resolver.Versions;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import cc.jumpkick.run.TaskContext;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
            case LockMode.Explicit(boolean sources) ->
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
     * pass will replace (null when there is none); it seeds conservative preferences, immutable git
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
        lock = ToolchainLockStamp.apply(
                lock,
                policy.keepToolchainSuggestion() ? existing : null,
                javaHome,
                jdkRegistry,
                pathPrep.project().project().jdkSpec(),
                pathPrep.project()
                        .nativeConfigOpt()
                        .map(NativeConfig::graalSpec)
                        .orElse(ToolchainSpec.NONE),
                pathPrep.project().graal() != null);
        if (profile) {
            ResolveProfile.phasePost(System.nanoTime() - postT0);
            System.err.println("jk: " + ResolveProfile.report());
        }
        return lock;
    }

    private Lockfile solve(
            LockOrchestrator orchestrator, JkBuild project, @Nullable Lockfile pins, ResolveObserver observer)
            throws Exception {
        if (policy.sources()) {
            return orchestrator.lockWithSources(project, jkVersion, features, withDefaults, observer);
        }
        if (pins != null) {
            // Invisible freshen: soft-prefer existing pins; the metadata TTL is fine (pins win).
            return orchestrator.lockConservative(project, pins, jkVersion, features, withDefaults, observer);
        }
        if (policy.forceRevalidate()) {
            // Float-to-latest needs current indexes; revalidate past the TTL (conditional GET).
            return MavenMetadataCache.withForceRevalidate(
                    () -> orchestrator.lock(project, jkVersion, features, withDefaults, observer));
        }
        // Local maven-metadata within TTL first (default 24h) — do not force-revalidate every
        // jk lock (conditional GETs still 429 Central on large graphs). Fresh indexes: jk update,
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
                d = PluginDescriptors.parse(
                        located.manifestToml(), located.path().toString(), false);
            } catch (Exception unparseable) {
                continue; // engine install already skipped this jar loudly
            }
            // Pin only plugins this project configures. Pinning every located plugin churned each
            // project's lock on every jk version bump and ping-ponged between developers on
            // different jk versions, for plugins the project never forks.
            if (effective.pluginConfig(d.table()).isEmpty()) continue;
            String hex;
            try {
                hex = Hashing.sha256Hex(located.path());
            } catch (IOException unreadable) {
                continue;
            }
            String coord = "cc.jumpkick:" + located.plugin().artifactId();
            if (seen.add(coord + ":" + JkVersion.VERSION)) {
                entries.add(new Lockfile.PluginEntry(coord, JkVersion.VERSION, "sha256:" + hex));
            }
            floor = PluginDescriptors.maxFloor(floor, PluginDescriptors.jkCompatFloor(d.jkCompat()));
        }
        return lock.withPlugins(entries).withJkMin(floor);
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
            PluginDescriptorOps.materialize(lockDir, hex, jarPath);
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
    private Path resolvePluginPath(String raw) {
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
            for (var lane : List.of(
                    PluginContributions.stepDependencies(effective, lockDir),
                    PluginContributions.commandDependencies(effective, lockDir))) {
                for (var sd : lane) {
                    if (sd.sdkComponent() != null && !"root".equals(sd.sdkComponent())) {
                        components.add(sd.sdkComponent());
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // no plugin tables / no contributions — nothing to pin
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
            } catch (RuntimeException ignored) {
                // non-Maven lock name
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
                System.err.println("jk: note: lock row " + pkg.name() + "@" + pkg.version()
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
            if (!RepoArtifactResolver.isNamedRemote(repoName)) return "";
            String m2Path = MavenLayout.artifactPath(pkg.coordinate());
            String stored = RepoArtifactStore.forRepoName(cas.root(), repoName)
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
        if (!effective.project().isKotlin()) return null;
        return highestMatch(
                effective.project().kotlin(),
                repos,
                Coordinate.of("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "any"));
    }

    /**
     * Resolve the project's {@code scala} version selector to a concrete Scala 3 compiler release.
     * Returns null for a non-Scala project or when resolution can't complete.
     */
    static @Nullable String resolveScalaVersion(JkBuild effective, RepoGroup repos) {
        if (!effective.project().isScala()) return null;
        return highestMatch(
                effective.project().scala(), repos, Coordinate.of("org.scala-lang", "scala3-compiler_3", "any"));
    }

    private static @Nullable String highestMatch(VersionSelector selector, RepoGroup repos, Coordinate coord) {
        if (selector instanceof VersionSelector.Exact exact) {
            return exact.version();
        }
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
    static @Nullable String pickVersion(VersionSet set, List<String> available) {
        return available.stream()
                .filter(set::contains)
                .filter(Versions::isStable)
                .max(Versions::compare)
                .or(() -> available.stream().filter(set::contains).max(Versions::compare))
                .orElse(null);
    }
}
