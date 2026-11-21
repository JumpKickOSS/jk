// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.Interned;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.resolve.ResolveProfile;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import org.jspecify.annotations.Nullable;

/**
 * Builds {@link EffectivePom}s: parent-chain merge, BOM import inlining, version backfill. Depth
 * capped at {@value #MAX_DEPTH}. Cache is concurrent so lock-time parallel materialize and parallel
 * BOM-import expansion can share one builder safely.
 */
public final class EffectivePomBuilder {

    static final int MAX_DEPTH = 16;

    private final RepoGroup repos;
    private final Map<String, Built> cache = new ConcurrentHashMap<>();

    /**
     * The memoised sources this builder's group is known to serve, by identity: a parent or BOM
     * shared by many models (a platform BOM under every starter) is asked of the group once per
     * builder, not once per memo hit.
     */
    private final Set<Source> served = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    /**
     * A model and whether it rests on a non-unique snapshot other than its own POM: a parent or an
     * imported BOM, at any depth, that a re-install or a deploy replaces in place. The memo's key
     * names only the POM's own file, so such a model is never kept past the builder that made it.
     */
    private record Built(
            EffectivePom pom, boolean moving, @Nullable Source source) {}

    /**
     * A POM file a model was built from: the coordinate asked for, the file a group served for it
     * ({@link #processKey}), and the sources of the parent and imported BOMs its model merged. Two
     * groups serving the same file may serve different parents or BOMs for it, so a memoised model
     * stands for a group only while that group serves every one of these files.
     */
    private record Source(Coordinate coord, String file, List<Source> inputs) {}

    /**
     * Process-wide effective-POM memo, keyed by the POM file the group served and its digest: two
     * groups — a POM that declares its own repositories makes another — that reach the same file
     * share one model, as long as the asking group also serves the parent and BOM files the model
     * was built from ({@link #servesInputsOf}); a group that does not builds its own. No TTL:
     * published release GAVs are immutable, and a model whose parent chain or BOM imports reach a
     * non-unique snapshot is not kept at all; force / {@link #clearProcessCache} drop the memo and
     * the idle engine drops it through {@link #dropProcessMemo}. Bounded by {@value #PROCESS_WEIGHT_MAX} weight of declared entries, the
     * least recently used going first.
     */
    private static final LruMemo<String, Built> PROCESS_CACHE =
            new LruMemo<>(EffectivePomBuilder.PROCESS_WEIGHT_MAX, built -> weight(built.pom()));

    /**
     * In-flight builds keyed by {@link #flightKey} so parallel warm/prefetch workers share one parent/BOM walk
     * instead of stampeding the same chain. Each entry is tagged with its owner thread so a
     * would-be joiner can detect a cross-thread wait cycle (two walkers each owning one half of a
     * mutually-referencing parent chain) and fall through to an independent in-line walk instead
     * of parking forever — the caller's own {@code visiting} set then trips the loud cycle
     * diagnostic.
     */
    private static final ConcurrentHashMap<String, Flight> IN_FLIGHT = new ConcurrentHashMap<>();

    /** An in-flight single-flight build and the thread performing it. */
    private record Flight(CompletableFuture<Built> future, Thread owner) {}

    /**
     * Flight key each thread is currently parked on in {@link #awaitShared}. Together with the
     * {@link Flight#owner()} tags this forms a waits-for graph: joiner → key → owner → key → …;
     * a chain that reaches the joiner itself means joining would deadlock.
     */
    private static final ConcurrentHashMap<Thread, String> WAITING_ON = new ConcurrentHashMap<>();

    /** Poll interval for in-flight joins; each wake re-checks the waits-for graph. */
    private static final long JOIN_POLL_MS = 25;

    /**
     * Hard cap on waiting for someone else's walk. The waits-for graph cannot see waits that pass
     * through plain futures (e.g. the parallel BOM fan-out), so after this bound we degrade to an
     * independent walk — at worst duplicate work, never a hang.
     */
    private static final long JOIN_FALLBACK_MS = 30_000;

    /** Declared entries (dependencies, managed rows, properties) the process memo holds at most. */
    static final long PROCESS_WEIGHT_MAX = 400_000;

    /** Drop the effective-POM memo and return how many models went; for the idle engine. */
    public static int dropProcessMemo() {
        return PROCESS_CACHE.clear();
    }

    /** Drop every process-wide resolve memo, the misses included (force / tests). */
    public static void clearProcessCache() {
        PROCESS_CACHE.clear();
        StorePoms.dropMemo();
        RepoArtifactStore.dropSidecarMemo();
        IN_FLIGHT.clear();
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
    }

    public EffectivePomBuilder(MavenRepo repo) {
        this(RepoGroup.of(repo));
    }

    public EffectivePomBuilder(RepoGroup repos) {
        this.repos = Objects.requireNonNull(repos, "repos");
    }

    /**
     * Effective model of an already-parsed POM: parent chain, BOM imports, and
     * {@code dependencyManagement} applied through this builder's repositories. Used when the POM
     * is in hand (sibling of a worker jar) rather than fetched by GAV.
     */
    public EffectivePom build(Pom raw) throws IOException, InterruptedException {
        Objects.requireNonNull(raw, "raw");
        return merge(raw, null, new HashSet<>(), 0).pom();
    }

    /**
     * Build (or return cached) effective POM. Concurrent-safe via {@link ConcurrentHashMap} cache;
     * each call uses its own cycle-detection set so sibling BOM imports can expand in parallel.
     */
    public EffectivePom build(Coordinate coord) throws IOException, InterruptedException {
        boolean profile = ResolveProfile.on();
        long t0 = profile ? Clock.SYSTEM.nanos() : 0L;
        boolean known = cache.containsKey(coord.toGav());
        EffectivePom built = buildInternal(coord, new HashSet<>(), 0).pom();
        if (profile) {
            ResolveProfile.pomBuild(known ? 0L : Clock.SYSTEM.nanos() - t0, known);
        }
        return built;
    }

    /** The POM file a group served and its digest: what an effective model is built from. */
    static String processKey(RepoGroup.RepoFetched hit) {
        return hit.fetched().cachePath().toAbsolutePath().normalize() + "|"
                + hit.fetched().sha256();
    }

    /**
     * An in-flight walk's key: the repositories asked and the GAV, known before the POM is fetched,
     * so a joiner can register in the waits-for graph before it has the file.
     */
    private String flightKey(Coordinate coord) {
        return repos.processIdentity() + "|" + coord.toGav();
    }

    /** What the process memo charges for a model: its declared entries. */
    static long weight(EffectivePom pom) {
        return 1L
                + pom.properties().size()
                + pom.dependencies().size()
                + pom.managedDependencies().size()
                + pom.inheritedManaged().size();
    }

    private Built buildInternal(Coordinate coord, Set<String> visiting, int depth)
            throws IOException, InterruptedException {
        if (depth > MAX_DEPTH) {
            throw new PomParseException("POM parent / BOM chain deeper than " + MAX_DEPTH + " at " + coord);
        }
        String localKey = coord.toGav();
        Built cached = cache.get(localKey);
        if (cached != null) return cached;
        RepoGroup.RepoFetched hit = repos.tryFetchPom(coord)
                .orElseThrow(() ->
                        new MavenRepo.ArtifactNotFoundException("POM not found in any declared repo: " + coord, coord));
        String processKey = processKey(hit);
        Built processHit = PROCESS_CACHE.get(processKey);
        if (processHit != null && servesInputsOf(Objects.requireNonNull(processHit.source()))) {
            cache.put(localKey, processHit);
            return processHit;
        }
        if (!visiting.add(localKey)) {
            throw new PomParseException("cycle in POM chain at " + localKey + " (already visiting: " + visiting + ")");
        }

        // Single-flight: parallel warm workers share one walk of each GAV (parents + BOM imports).
        // In-flight keys include the repo identity so two groups never share a partial walk.
        String flightKey = flightKey(coord);
        try {
            Flight mine = new Flight(new CompletableFuture<>(), Thread.currentThread());
            Flight existing = IN_FLIGHT.putIfAbsent(flightKey, mine);
            if (existing != null) {
                Built shared = awaitShared(localKey, flightKey, existing);
                if (shared != null) {
                    cache.put(localKey, shared);
                    return shared;
                }
                // Joining would deadlock (the owner chain waits back on us) or the owner died
                // without completing. Build in-line with our own visiting set — still registered
                // above — so a real POM cycle trips the single-thread check and throws the loud
                // cycle diagnostic instead of parking forever. Worst case: duplicate work.
                return parseAndMerge(coord, hit, processKey, visiting, depth, null);
            }
            try {
                return parseAndMerge(coord, hit, processKey, visiting, depth, mine.future());
            } catch (IOException | InterruptedException | RuntimeException e) {
                mine.future().completeExceptionally(e);
                throw e;
            } finally {
                IN_FLIGHT.remove(flightKey, mine);
            }
        } finally {
            visiting.remove(localKey);
        }
    }

    /** Parse + merge one served POM; publishes to caches and completes {@code flight} when non-null. */
    private Built parseAndMerge(
            Coordinate coord,
            RepoGroup.RepoFetched hit,
            String processKey,
            Set<String> visiting,
            int depth,
            @Nullable CompletableFuture<Built> flight)
            throws IOException, InterruptedException {
        Pom raw = StorePoms.parse(hit.fetched().cachePath());
        Built effective = merge(raw, new Source(coord, processKey, List.of()), visiting, depth);
        cache.put(coord.toGav(), effective);
        if (!effective.moving() && PROCESS_CACHE.get(processKey) == null) {
            PROCESS_CACHE.put(processKey, effective);
        }
        if (flight != null) {
            flight.complete(effective);
        }
        return effective;
    }

    /**
     * Wait on another thread's in-flight walk. Returns the shared result, or {@code null} when
     * joining is unsafe — the waits-for chain loops back to this thread, the owner died without
     * completing, or the generous {@link #JOIN_FALLBACK_MS} bound elapsed — in which case the
     * caller degrades to an independent in-line walk.
     */
    private static @Nullable Built awaitShared(String localKey, String flightKey, Flight flight)
            throws IOException, InterruptedException {
        Thread self = Thread.currentThread();
        WAITING_ON.put(self, flightKey);
        try {
            long deadline = Clock.SYSTEM.nanos() + TimeUnit.MILLISECONDS.toNanos(JOIN_FALLBACK_MS);
            while (true) {
                if (joinWouldDeadlock(self, flight)) return null;
                if (!flight.future().isDone() && !flight.owner().isAlive()) return null;
                if (!flight.future().isDone() && Clock.SYSTEM.nanos() - deadline > 0) return null;
                try {
                    return flight.future().get(JOIN_POLL_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // Re-check the waits-for graph and owner liveness, then park again.
                } catch (ExecutionException e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    if (c instanceof IOException io) throw io;
                    if (c instanceof InterruptedException ie) throw ie;
                    if (c instanceof RuntimeException re) throw re;
                    if (c instanceof Error err) throw err;
                    throw new IOException("effective POM build failed for " + localKey, c);
                }
            }
        } finally {
            WAITING_ON.remove(self);
        }
    }

    /**
     * Walks the waits-for graph (joiner → in-flight key → owner thread → …) from {@code flight}'s
     * owner; reaching {@code self} means parking on this flight can never end.
     */
    private static boolean joinWouldDeadlock(Thread self, Flight flight) {
        Thread owner = flight.owner();
        Set<Thread> seen = new HashSet<>();
        while (owner != null && seen.add(owner)) {
            if (owner == self) return true;
            String key = WAITING_ON.get(owner);
            if (key == null) return false;
            Flight next = IN_FLIGHT.get(key);
            if (next == null) return false;
            owner = next.owner();
        }
        return false;
    }

    /** {@code child}'s parent model, moving when the parent is itself a non-unique snapshot; null without one. */
    private @Nullable Built parentOf(Pom child, Set<String> visiting, int depth)
            throws IOException, InterruptedException {
        Pom.Parent p = child.parent();
        if (p == null) return null;
        Coordinate coord = Coordinate.of(p.groupId(), p.artifactId(), p.version());
        Built built = buildInternal(coord, visiting, depth + 1);
        return new Built(built.pom(), built.moving() || MovingSnapshots.moving(coord), built.source());
    }

    /**
     * True when this group serves, for every coordinate a memoised model's parents and BOMs were
     * fetched as, the same file the model was built from: then a walk through this group would
     * merge the same files into the same model.
     */
    private boolean servesInputsOf(Source source) throws IOException, InterruptedException {
        Set<Source> checked = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Source> pending = new ArrayList<>(source.inputs());
        while (!pending.isEmpty()) {
            Source input = pending.removeLast();
            if (served.contains(input) || !checked.add(input)) continue;
            RepoGroup.RepoFetched hit = repos.tryFetchPom(input.coord()).orElse(null);
            if (hit == null || !processKey(hit).equals(input.file())) return false;
            pending.addAll(input.inputs());
        }
        served.addAll(checked);
        return true;
    }

    /**
     * {@code child}'s effective model. {@code self} names the file it was read from, its inputs
     * left for this merge to fill; null for a POM in hand, whose model is never memoised.
     */
    private Built merge(Pom child, @Nullable Source self, Set<String> visiting, int depth)
            throws IOException, InterruptedException {
        List<Source> inputs = new ArrayList<>();

        Built parentBuilt = parentOf(child, visiting, depth);
        EffectivePom parent = parentBuilt == null ? null : parentBuilt.pom();
        boolean moving = parentBuilt != null && parentBuilt.moving();
        if (parentBuilt != null && parentBuilt.source() != null) inputs.add(parentBuilt.source());

        // 1. Coords — child wins, parent fills holes.
        String groupId = child.groupId() != null ? child.groupId() : (parent != null ? parent.groupId() : null);
        String version = child.version() != null ? child.version() : (parent != null ? parent.version() : null);
        if (groupId == null || version == null) {
            throw new PomParseException("cannot determine effective groupId/version for " + child.artifactId());
        }

        // 2. Properties — host platform words first, parent next, child overrides. The implicit
        // project.* and project.parent.* always come from the child: Maven interpolates an inherited
        // model in the child's context, so a parent's managed version spelled
        // ${project.parent.version} values to the version of the parent the child names.
        Map<String, String> props = new LinkedHashMap<>(HostClassifiers.properties());
        if (parent != null) props.putAll(parent.properties());
        props.putAll(child.properties());
        PomParser.putImplicitProperties(props, groupId, child.artifactId(), version, child.packaging(), child.parent());

        // 3. Managed deps, with Maven's precedence: entries the chain declares itself (parent
        // first, then the child; later wins) beat anything an import supplies, and imports fill
        // only the keys still open — the child's imports in declaration order, then the ones the
        // parent inherited, first wins. The imports are expanded in parallel, then spliced back in
        // that order.
        // Two views of the declared entries: the child's own, valued in its context, and the ones a
        // child of this POM inherits, with their properties as written for that child to value.
        List<Pom.Dep> declaredManaged = new ArrayList<>();
        List<Pom.Dep> declaredInheritable = new ArrayList<>();
        List<Pom.Dep> importedManaged = new ArrayList<>();
        if (parent != null) {
            for (Pom.Dep m : parent.inheritedManaged()) {
                // The parent's imports are expanded again below, in this POM's context.
                if (parent.importedManagedKeys().contains(depKey(m))) continue;
                declaredManaged.add(m);
                declaredInheritable.add(m);
            }
        }
        // Every import of the chain as written, this POM's first, then the ones it inherits: each
        // is valued with this POM's properties, so a child that bumps a BOM's version property
        // imports the BOM it names.
        List<Pom.Dep> imports = new ArrayList<>();
        for (Pom.Dep dep : child.inheritableManaged()) {
            if (isBomImport(dep)) imports.add(dep);
        }
        if (parent != null) imports.addAll(parent.inheritedImports());
        List<Coordinate> bomCoordsOrdered = new ArrayList<>();
        for (Pom.Dep dep : imports) bomCoordsOrdered.add(bomCoordinate(dep, props));
        Map<String, Built> bomsByGav = buildBomImportsParallel(bomCoordsOrdered, visiting, depth + 1);
        for (Coordinate bomCoord : bomCoordsOrdered) {
            Built bom = bomsByGav.get(bomCoord.toGav());
            if (bom == null) {
                // Should not happen; fall back to serial expand.
                bom = buildInternal(bomCoord, visiting, depth + 1);
            }
            moving |= bom.moving() || MovingSnapshots.moving(bomCoord);
            if (bom.source() != null) inputs.add(bom.source());
            importedManaged.addAll(bom.pom().managedDependencies());
        }
        for (Pom.Dep dep : child.managedDependencies()) {
            if (!isBomImport(dep)) declaredManaged.add(dep);
        }
        for (Pom.Dep dep : child.inheritableManaged()) {
            if (!isBomImport(dep)) declaredInheritable.add(dep);
        }
        List<Pom.Dep> mergedManaged = dedupeByModule(declaredManaged);
        List<Pom.Dep> inheritedManaged = dedupeByModule(declaredInheritable);
        Set<String> declaredKeys = new HashSet<>();
        for (Pom.Dep m : mergedManaged) declaredKeys.add(depKey(m));
        Set<String> importedKeys = new HashSet<>();
        for (Pom.Dep m : importedManaged) {
            if (declaredKeys.contains(depKey(m))) continue;
            if (importedKeys.add(depKey(m))) {
                mergedManaged.add(m);
                inheritedManaged.add(m);
            }
        }
        mergedManaged = substituteAll(mergedManaged, props);

        // 4. Effective deps — parent first, child overrides by module. Valued before they are
        // keyed: a coordinate spelled with a parent's property names its module only once valued,
        // both for the override and for the management lookup below.
        List<Pom.Dep> mergedDeps = new ArrayList<>();
        if (parent != null) mergedDeps.addAll(parent.dependencies());
        mergedDeps.addAll(child.dependencies());
        mergedDeps = dedupeByModule(substituteAll(mergedDeps, props));
        Map<String, String> hostClassified = hostClassified(child.dependencies(), parent, props);

        // 5. Apply dependencyManagement defaults. Maven fills in not just the
        // version but the scope / type / classifier / exclusions a dep leaves
        // unspecified — e.g. google-java-format declares guava-testlib with no
        // version OR scope, inheriting <scope>test</scope> from its parent's
        // management; backfilling only the version would leak it (and the
        // guava-android it drags in) onto the compile classpath.
        Map<String, Pom.Dep> managedByModule = new HashMap<>();
        for (Pom.Dep m : mergedManaged) managedByModule.put(depKey(m), m);
        List<Pom.Dep> finalDeps = new ArrayList<>(mergedDeps.size());
        for (Pom.Dep dep : mergedDeps) {
            Pom.Dep managed = managedByModule.get(depKey(dep));
            if (managed != null) {
                dep = applyManagedDefaults(dep, managed);
            }
            finalDeps.add(dep);
        }

        // retain dependencyManagement only on packaging=pom (parents/BOMs). Jar/war
        // artifacts already had management applied into finalDeps; keeping a full flattened
        // managed list (~2k entries for quarkus-bom parents) on every GAV dominated engine heap.
        // A table equal to the parent's is the parent's: an intermediate that manages nothing of
        // its own shares its parent's list and key set rather than holding a second copy of each.
        boolean pomPackaging = "pom".equalsIgnoreCase(child.packaging());
        List<Pom.Dep> retainedManaged = !pomPackaging
                ? List.of()
                : parent != null && mergedManaged.equals(parent.managedDependencies())
                        ? parent.managedDependencies()
                        : mergedManaged;
        List<Pom.Dep> retainedInherited = !pomPackaging
                ? List.of()
                : inheritedManaged.equals(retainedManaged)
                        ? retainedManaged
                        : parent != null && inheritedManaged.equals(parent.inheritedManaged())
                                ? parent.inheritedManaged()
                                : inheritedManaged;
        Set<String> retainedImportedKeys = !pomPackaging
                ? Set.of()
                : parent != null && importedKeys.equals(parent.importedManagedKeys())
                        ? parent.importedManagedKeys()
                        : importedKeys;

        // Same rule for properties: the flattened ancestor map is only read when this
        // POM serves as a parent or BOM — always packaging=pom (Maven rejects non-pom parents).
        // Jar/war artifacts had ${…} substitution applied into their dep lists above; retaining
        // the whole ancestor flatten (~200 entries for spring/quarkus parents) on every GAV in
        // the process memo multiplied parent maps across the engine heap. They keep their own
        // declared properties plus the implicit project.* entries.
        Map<String, String> retainedProps = props;
        if (!"pom".equalsIgnoreCase(child.packaging())) {
            retainedProps = new LinkedHashMap<>(child.properties());
            PomParser.putImplicitProperties(
                    retainedProps, groupId, child.artifactId(), version, child.packaging(), child.parent());
        }

        // A relocation belongs to the POM that declares it — it is not inherited from a parent.
        return new Built(
                new EffectivePom(
                        groupId,
                        child.artifactId(),
                        version,
                        child.packaging(),
                        retainedProps,
                        finalDeps,
                        retainedManaged,
                        retainedImportedKeys,
                        relocation(child.relocation(), props),
                        hostClassified,
                        repositories(child, parent, props),
                        retainedInherited,
                        !child.licenses().isEmpty() || parent == null ? child.licenses() : parent.licenses(),
                        pomPackaging ? List.copyOf(imports) : List.of()),
                moving,
                self == null ? null : new Source(self.coord(), self.file(), List.copyOf(inputs)));
    }

    /** {@code moved} with its fields valued from the chain's properties. */
    private static Pom.@Nullable Relocation relocation(Pom.@Nullable Relocation moved, Map<String, String> props) {
        if (moved == null) return null;
        return new Pom.Relocation(
                substituteOrNull(moved.groupId(), props),
                substituteOrNull(moved.artifactId(), props),
                substituteOrNull(moved.version(), props),
                substituteOrNull(moved.message(), props));
    }

    /**
     * The child's own {@code <repositories>} first, then the parent chain's, one entry per URL. A
     * child's URL is valued from the chain's properties here; one still naming a property, or
     * naming Central, is dropped.
     */
    private static List<Pom.Repository> repositories(
            Pom child, @Nullable EffectivePom parent, Map<String, String> props) {
        if (child.repositories().isEmpty()
                && (parent == null || parent.repositories().isEmpty())) return List.of();
        LinkedHashMap<String, Pom.Repository> byUrl = new LinkedHashMap<>();
        for (Pom.Repository r : child.repositories()) {
            String url = substitute(r.url(), props).trim();
            if (url.contains("${") || PomParser.isCentral(url)) continue;
            String id = r.id().equals(r.url()) ? url : r.id();
            byUrl.putIfAbsent(url, new Pom.Repository(id, url, r.declaredBy(), r.releases(), r.snapshots()));
        }
        if (parent != null) {
            for (Pom.Repository r : parent.repositories()) byUrl.putIfAbsent(r.url(), r);
        }
        return new ArrayList<>(byUrl.values());
    }

    /**
     * The dependencies whose classifier a host property (and not the chain's own properties) filled,
     * keyed by valued module, valued by the {@code ${...}} expression. The child's own are read off
     * its classifiers as written. The parent's arrive valued, so they come from the parent's map,
     * except one the child redeclares, which replaces it.
     */
    private static Map<String, String> hostClassified(
            List<Pom.Dep> own, @Nullable EffectivePom parent, Map<String, String> props) {
        Map<String, String> out = new LinkedHashMap<>();
        if (parent != null && !parent.hostClassified().isEmpty()) {
            Set<String> ownKeys = new HashSet<>();
            for (Pom.Dep dep : substituteAll(own, props)) ownKeys.add(depKey(dep));
            for (Pom.Dep dep : parent.dependencies()) {
                String expression = parent.hostClassified().get(dep.module());
                if (expression != null && !ownKeys.contains(depKey(dep))) {
                    out.put(dep.module(), expression);
                }
            }
        }
        Map<String, String> hostWords = HostClassifiers.properties();
        for (Pom.Dep dep : own) {
            String classifier = dep.classifier();
            if (classifier == null) continue;
            Matcher m = PomInterpolation.PROPERTY_REF.matcher(classifier);
            while (m.find()) {
                String name = m.group(1);
                if (HostClassifiers.names(name) && Objects.equals(props.get(name), hostWords.get(name))) {
                    out.put(substitute(dep.groupId(), props) + ":" + substitute(dep.artifactId(), props), classifier);
                }
            }
        }
        return out;
    }

    // --- merge helpers -----------------------------------------------------

    /**
     * Expand unique {@code import}-scoped BOM POMs keyed by GAV. One unique BOM stays on the caller
     * thread; several run on {@link JkThreads#io} with independent cycle sets.
     */
    private Map<String, Built> buildBomImportsParallel(List<Coordinate> bomCoords, Set<String> visiting, int depth)
            throws IOException, InterruptedException {
        // Dedupe while preserving first-seen order.
        LinkedHashMap<String, Coordinate> unique = new LinkedHashMap<>();
        for (Coordinate c : bomCoords) {
            unique.putIfAbsent(c.toGav(), c);
        }
        if (unique.isEmpty()) return Map.of();
        if (unique.size() == 1) {
            Coordinate only = unique.values().iterator().next();
            return Map.of(only.toGav(), buildInternal(only, visiting, depth));
        }
        Map<String, CompletableFuture<Built>> futures = new LinkedHashMap<>();
        for (var e : unique.entrySet()) {
            Set<String> childVisiting = new HashSet<>(visiting);
            Coordinate bomCoord = e.getValue();
            futures.put(
                    e.getKey(),
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return buildInternal(bomCoord, childVisiting, depth);
                                } catch (IOException | InterruptedException ex) {
                                    throw new CompletionException(ex);
                                }
                            },
                            JkThreads.io()));
        }
        Map<String, Built> out = new LinkedHashMap<>();
        Thread self = Thread.currentThread();
        try {
            joinExpansions(unique, futures, out, self);
        } finally {
            // Quiesce before returning OR throwing: an expansion future this thread broke away
            // from (sibling threw, bound elapsed) keeps running on the pool and writes into the
            // repo cache after the caller has moved on — test @TempDir cleanup raced exactly that.
            // The drain waits the way the join does, in the waits-for graph: an expansion parked on
            // a flight whose owner waits on this thread's own walk can only finish after this
            // thread does, so waiting on it would end only at the bound.
            long drainDeadline = Clock.SYSTEM.nanos() + TimeUnit.MILLISECONDS.toNanos(JOIN_FALLBACK_MS);
            for (var e : futures.entrySet()) {
                if (e.getValue().isDone()) continue;
                String bomKey = flightKey(Objects.requireNonNull(unique.get(e.getKey())));
                try {
                    awaitExpansion(bomKey, e.getValue(), self, drainDeadline);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException ignored) {
                    // outcome irrelevant — only quiescence matters here
                }
            }
        }
        return out;
    }

    /**
     * Wait for one BOM expansion while registered in the waits-for graph as waiting on its flight.
     * Returns its result, or {@code null} when the wait loops back to {@code self} (a cycle the
     * caller resolves by expanding in-line) or {@code deadline} passed.
     */
    private static @Nullable Built awaitExpansion(
            String bomKey, CompletableFuture<Built> future, Thread self, long deadline)
            throws ExecutionException, InterruptedException {
        WAITING_ON.put(self, bomKey);
        try {
            while (true) {
                try {
                    return future.get(JOIN_POLL_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    Flight f = IN_FLIGHT.get(bomKey);
                    if (f != null && f.owner() != self && joinWouldDeadlock(self, f)) return null;
                    if (Clock.SYSTEM.nanos() - deadline > 0) return null;
                }
            }
        } finally {
            WAITING_ON.remove(self);
        }
    }

    private void joinExpansions(
            LinkedHashMap<String, Coordinate> unique,
            Map<String, CompletableFuture<Built>> futures,
            Map<String, Built> out,
            Thread self)
            throws IOException, InterruptedException {
        for (var e : futures.entrySet()) {
            // Registered in the waits-for graph: a worker whose own await chain reaches this thread
            // sees the loop and detects it at once. If WE detect it (or the bound elapses), the entry
            // is left out and merge()'s serial-expand fallback builds the BOM in-line with this
            // thread's visiting set — a real POM cycle then throws the loud cycle diagnostic.
            String bomKey = flightKey(Objects.requireNonNull(unique.get(e.getKey())));
            long deadline = Clock.SYSTEM.nanos() + TimeUnit.MILLISECONDS.toNanos(JOIN_FALLBACK_MS);
            try {
                Built bom = awaitExpansion(bomKey, e.getValue(), self, deadline);
                if (bom != null) out.put(e.getKey(), bom);
            } catch (ExecutionException ex) {
                Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                if (c instanceof IOException io) throw io;
                if (c instanceof InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                if (c instanceof RuntimeException re) throw re;
                if (c instanceof Error err) throw err;
                throw new IOException(c);
            }
        }
    }

    private static boolean isBomImport(Pom.Dep dep) {
        return "import".equals(dep.scope()) && "pom".equals(dep.type());
    }

    /**
     * Keeps the last occurrence per dependency key, preserving insertion order otherwise. Maven's
     * dependency identity is {@code groupId:artifactId:type:classifier}, NOT just the module
     * logback-parent manages {@code logback-core} twice (the jar and a {@code test-jar}), and
     * logback-classic depends on both; collapsing on module alone let the test-jar row overwrite
     * the real one, silently dropping {@code logback-core} from every Boot classpath.
     */
    private static List<Pom.Dep> dedupeByModule(List<Pom.Dep> deps) {
        LinkedHashMap<String, Pom.Dep> byModule = new LinkedHashMap<>();
        for (Pom.Dep dep : deps) byModule.put(depKey(dep), dep);
        return new ArrayList<>(byModule.values());
    }

    /** Maven dependency identity: {@code groupId:artifactId:type:classifier} (type defaults to jar). */
    static String depKey(Pom.Dep dep) {
        String type = blank(dep.type()) ? "jar" : dep.type();
        String classifier = blank(dep.classifier()) ? "" : dep.classifier();
        return dep.module() + ":" + type + ":" + classifier;
    }

    private static List<Pom.Dep> substituteAll(List<Pom.Dep> deps, Map<String, String> props) {
        List<Pom.Dep> out = new ArrayList<>(deps.size());
        for (Pom.Dep d : deps) out.add(substitute(d, props));
        return out;
    }

    /**
     * {@code d} with every {@code ${...}} its fields spell valued from {@code props} — and {@code d}
     * itself when they spell none. A BOM's managed table is valued once, in the BOM's own merge;
     * every POM that imports or inherits it then carries the same {@link Pom.Dep} objects rather
     * than a copy per table, which is what keeps a reactor whose every module chains to a
     * three-thousand-row BOM inside the engine heap.
     */
    private static Pom.Dep substitute(Pom.Dep d, Map<String, String> props) {
        String groupId = substitute(d.groupId(), props);
        String artifactId = substitute(d.artifactId(), props);
        String version = substituteOrNull(d.version(), props);
        String scope = substituteOrNull(d.scope(), props);
        String classifier = substituteOrNull(d.classifier(), props);
        String type = substituteOrNull(d.type(), props);
        List<Pom.Dep.Exclusion> exclusions = substituteExclusions(d.exclusions(), props);
        boolean unchanged = groupId == d.groupId()
                && artifactId == d.artifactId()
                && version == d.version()
                && scope == d.scope()
                && classifier == d.classifier()
                && type == d.type()
                && exclusions == d.exclusions();
        if (unchanged) return d;
        return new Pom.Dep(
                Interned.of(groupId),
                Interned.of(artifactId),
                Interned.ofNullable(version),
                Interned.ofNullable(scope),
                d.optional(),
                Interned.ofNullable(classifier),
                Interned.ofNullable(type),
                exclusions);
    }

    /** {@code exclusions} with their properties valued; the same list when none names one. */
    private static List<Pom.Dep.Exclusion> substituteExclusions(
            List<Pom.Dep.Exclusion> exclusions, Map<String, String> props) {
        List<Pom.Dep.Exclusion> out = null;
        for (int i = 0; i < exclusions.size(); i++) {
            Pom.Dep.Exclusion e = exclusions.get(i);
            String groupId = substitute(e.groupId(), props);
            String artifactId = substitute(e.artifactId(), props);
            if (out == null && groupId == e.groupId() && artifactId == e.artifactId()) continue;
            if (out == null) out = new ArrayList<>(exclusions.subList(0, i));
            out.add(
                    groupId == e.groupId() && artifactId == e.artifactId()
                            ? e
                            : new Pom.Dep.Exclusion(Interned.of(groupId), Interned.of(artifactId)));
        }
        return out == null ? exclusions : out;
    }

    /**
     * Fill in the version / scope / type / classifier / exclusions a dependency leaves unspecified
     * from its {@code dependencyManagement} entry (a declared value on the dependency itself always
     * wins). Mirrors Maven: management supplies defaults for all of these, not just the version.
     */
    private static Pom.Dep applyManagedDefaults(Pom.Dep dep, Pom.Dep managed) {
        String version = blank(dep.version()) ? managed.version() : dep.version();
        String scope = blank(dep.scope()) ? managed.scope() : dep.scope();
        String type = blank(dep.type()) ? managed.type() : dep.type();
        String classifier = blank(dep.classifier()) ? managed.classifier() : dep.classifier();
        List<Pom.Dep.Exclusion> exclusions =
                (dep.exclusions() == null || dep.exclusions().isEmpty()) ? managed.exclusions() : dep.exclusions();
        return new Pom.Dep(
                dep.groupId(), dep.artifactId(), version, scope, dep.optional(), classifier, type, exclusions);
    }

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    /**
     * The BOM a {@code <scope>import</scope>} entry names, every field valued from the chain's
     * properties before the coordinate is formed: a groupId spelled {@code ${ee.maven.groupId}}
     * whose value is itself {@code ${project.groupId}} is the group it chains to, never a
     * repository path carrying the placeholder. Maven requires the version on an import.
     */
    private static Coordinate bomCoordinate(Pom.Dep dep, Map<String, String> props) {
        String version = dep.version();
        if (version == null) {
            throw new IllegalStateException("BOM import " + dep.groupId() + ":" + dep.artifactId() + " has no version");
        }
        return Coordinate.of(
                substitute(dep.groupId(), props), substitute(dep.artifactId(), props), substitute(version, props));
    }

    private static String substitute(String raw, Map<String, String> ctx) {
        return Objects.requireNonNull(substituteOrNull(raw, ctx));
    }

    private static @Nullable String substituteOrNull(@Nullable String raw, Map<String, String> ctx) {
        return PomInterpolation.interpolate(raw, ctx);
    }
}
