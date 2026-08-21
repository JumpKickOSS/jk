// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import java.util.regex.Pattern;

/**
 * Builds {@link EffectivePom}s: parent-chain merge, BOM import inlining, version backfill. Depth
 * capped at {@value #MAX_DEPTH}. Cache is concurrent so lock-time parallel materialize and parallel
 * BOM-import expansion can share one builder safely.
 */
public final class EffectivePomBuilder {

    static final int MAX_DEPTH = 16;
    private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)\\}");

    private final RepoGroup repos;
    private final Map<String, EffectivePom> cache = new ConcurrentHashMap<>();

    /**
     * Process-wide effective-POM memo. Keyed by the repositories asked <em>and</em> GAV — parent
     * and BOM walks use this group's repo set, so one group's answer cannot stand in for another's.
     * No TTL: published release GAVs are immutable; force / {@link #clearProcessCache} drop the
     * memo. Capped so a long-lived engine cannot retain unbounded POM graphs.
     */
    private static final ConcurrentHashMap<String, EffectivePom> PROCESS_CACHE = new ConcurrentHashMap<>();

    /**
     * In-flight builds keyed by GAV so parallel warm/prefetch workers share one parent/BOM walk
     * instead of stampeding the same chain. Each entry is tagged with its owner thread so a
     * would-be joiner can detect a cross-thread wait cycle (two walkers each owning one half of a
     * mutually-referencing parent chain) and fall through to an independent in-line walk instead
     * of parking forever — the caller's own {@code visiting} set then trips the loud cycle
     * diagnostic.
     */
    private static final ConcurrentHashMap<String, Flight> IN_FLIGHT = new ConcurrentHashMap<>();

    /** An in-flight single-flight build and the thread performing it. */
    private record Flight(CompletableFuture<EffectivePom> future, Thread owner) {}

    /**
     * Process key each thread is currently parked on in {@link #awaitShared}. Together with the
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

    private static final int PROCESS_CACHE_MAX = 8_192;

    /** Drop process-wide memo (tests; never required in production). */
    public static void clearProcessCache() {
        PROCESS_CACHE.clear();
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
        return merge(raw, new HashSet<>(), 0);
    }

    /**
     * Build (or return cached) effective POM. Concurrent-safe via {@link ConcurrentHashMap} cache;
     * each call uses its own cycle-detection set so sibling BOM imports can expand in parallel.
     */
    public EffectivePom build(Coordinate coord) throws IOException, InterruptedException {
        boolean profile = cc.jumpkick.resolve.ResolveProfile.on();
        long t0 = profile ? System.nanoTime() : 0L;
        String localKey = coord.toGav();
        String processKey = processKey(coord);
        boolean known = cache.containsKey(localKey) || PROCESS_CACHE.containsKey(processKey);
        EffectivePom built = buildInternal(coord, new HashSet<>(), 0);
        if (profile) {
            cc.jumpkick.resolve.ResolveProfile.pomBuild(known ? 0L : System.nanoTime() - t0, known);
        }
        return built;
    }

    private String processKey(Coordinate coord) {
        return repos.processIdentity() + "|" + coord.toGav();
    }

    private EffectivePom buildInternal(Coordinate coord, Set<String> visiting, int depth)
            throws IOException, InterruptedException {
        if (depth > MAX_DEPTH) {
            throw new PomParseException("POM parent / BOM chain deeper than " + MAX_DEPTH + " at " + coord);
        }
        String localKey = coord.toGav();
        String processKey = processKey(coord);
        EffectivePom cached = cache.get(localKey);
        if (cached != null) return cached;
        EffectivePom processHit = PROCESS_CACHE.get(processKey);
        if (processHit != null) {
            cache.put(localKey, processHit);
            return processHit;
        }
        if (!visiting.add(localKey)) {
            throw new PomParseException("cycle in POM chain at " + localKey + " (already visiting: " + visiting + ")");
        }

        // Single-flight: parallel warm workers share one walk of each GAV (parents + BOM imports).
        // In-flight keys include the repo identity so two groups never share a partial walk.
        try {
            Flight mine = new Flight(new CompletableFuture<>(), Thread.currentThread());
            Flight existing = IN_FLIGHT.putIfAbsent(processKey, mine);
            if (existing != null) {
                EffectivePom shared = awaitShared(localKey, processKey, existing);
                if (shared != null) {
                    cache.put(localKey, shared);
                    return shared;
                }
                // Joining would deadlock (the owner chain waits back on us) or the owner died
                // without completing. Build in-line with our own visiting set — still registered
                // above — so a real POM cycle trips the single-thread check and throws the loud
                // cycle diagnostic instead of parking forever. Worst case: duplicate work.
                return fetchAndMerge(coord, localKey, processKey, visiting, depth, null);
            }
            try {
                return fetchAndMerge(coord, localKey, processKey, visiting, depth, mine.future());
            } catch (IOException | InterruptedException | RuntimeException e) {
                mine.future().completeExceptionally(e);
                throw e;
            } finally {
                IN_FLIGHT.remove(processKey, mine);
            }
        } finally {
            visiting.remove(localKey);
        }
    }

    /** Fetch + merge one GAV; publishes to caches and completes {@code flight} when non-null. */
    private EffectivePom fetchAndMerge(
            Coordinate coord,
            String localKey,
            String processKey,
            Set<String> visiting,
            int depth,
            CompletableFuture<EffectivePom> flight)
            throws IOException, InterruptedException {
        RepoGroup.RepoFetched hit = repos.tryFetchPom(coord)
                .orElseThrow(() ->
                        new MavenRepo.ArtifactNotFoundException("POM not found in any declared repo: " + coord, coord));
        Pom raw = PomParser.parse(Files.readAllBytes(hit.fetched().cachePath()));
        EffectivePom effective = merge(raw, visiting, depth);
        cache.put(localKey, effective);
        if (PROCESS_CACHE.size() < PROCESS_CACHE_MAX) {
            PROCESS_CACHE.putIfAbsent(processKey, effective);
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
    private static EffectivePom awaitShared(String localKey, String processKey, Flight flight)
            throws IOException, InterruptedException {
        Thread self = Thread.currentThread();
        WAITING_ON.put(self, processKey);
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(JOIN_FALLBACK_MS);
            while (true) {
                if (joinWouldDeadlock(self, flight)) return null;
                if (!flight.future().isDone() && !flight.owner().isAlive()) return null;
                if (!flight.future().isDone() && System.nanoTime() - deadline > 0) return null;
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

    private EffectivePom merge(Pom child, Set<String> visiting, int depth) throws IOException, InterruptedException {

        EffectivePom parent = null;
        if (child.parent() != null) {
            Pom.Parent p = child.parent();
            parent = buildInternal(Coordinate.of(p.groupId(), p.artifactId(), p.version()), visiting, depth + 1);
        }

        // 1. Coords — child wins, parent fills holes.
        String groupId = child.groupId() != null ? child.groupId() : (parent != null ? parent.groupId() : null);
        String version = child.version() != null ? child.version() : (parent != null ? parent.version() : null);
        if (groupId == null || version == null) {
            throw new PomParseException("cannot determine effective groupId/version for " + child.artifactId());
        }

        // 2. Properties — parent first, child overrides. Implicit project.* always come from child.
        Map<String, String> props = new LinkedHashMap<>();
        if (parent != null) props.putAll(parent.properties());
        props.putAll(child.properties());
        props.put("project.groupId", groupId);
        props.put("project.artifactId", child.artifactId());
        props.put("project.version", version);
        props.put("project.packaging", child.packaging());

        // 3. Managed deps — parent first, then child in declaration order (later wins via dedupe).
        // BOM imports: prefetch/expand unique BOM POMs in parallel, then splice results
        // back in original order so override semantics stay Maven-correct.
        List<Pom.Dep> mergedManaged = new ArrayList<>();
        if (parent != null) mergedManaged.addAll(parent.managedDependencies());
        List<Coordinate> bomCoordsOrdered = new ArrayList<>();
        for (Pom.Dep dep : child.managedDependencies()) {
            if (isBomImport(dep)) {
                bomCoordsOrdered.add(Coordinate.of(dep.groupId(), dep.artifactId(), substitute(dep.version(), props)));
            }
        }
        Map<String, EffectivePom> bomsByGav = buildBomImportsParallel(bomCoordsOrdered, visiting, depth + 1);
        for (Pom.Dep dep : child.managedDependencies()) {
            if (isBomImport(dep)) {
                Coordinate bomCoord = Coordinate.of(dep.groupId(), dep.artifactId(), substitute(dep.version(), props));
                EffectivePom bom = bomsByGav.get(bomCoord.toGav());
                if (bom == null) {
                    // Should not happen; fall back to serial expand.
                    bom = buildInternal(bomCoord, visiting, depth + 1);
                }
                mergedManaged.addAll(bom.managedDependencies());
            } else {
                mergedManaged.add(dep);
            }
        }
        mergedManaged = substituteAll(dedupeByModule(mergedManaged), props);

        // 4. Effective deps — parent first, child overrides by module.
        List<Pom.Dep> mergedDeps = new ArrayList<>();
        if (parent != null) mergedDeps.addAll(parent.dependencies());
        mergedDeps.addAll(child.dependencies());
        mergedDeps = dedupeByModule(mergedDeps);

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
        finalDeps = substituteAll(finalDeps, props);

        // retain dependencyManagement only on packaging=pom (parents/BOMs). Jar/war
        // artifacts already had management applied into finalDeps; keeping a full flattened
        // managed list (~2k entries for quarkus-bom parents) on every GAV dominated engine heap.
        List<Pom.Dep> retainedManaged = "pom".equalsIgnoreCase(child.packaging()) ? mergedManaged : List.of();

        // Same rule for properties: the flattened ancestor map is only read when this
        // POM serves as a parent or BOM — always packaging=pom (Maven rejects non-pom parents).
        // Jar/war artifacts had ${…} substitution applied into their dep lists above; retaining
        // the whole ancestor flatten (~200 entries for spring/quarkus parents) on every GAV in
        // the 8192-entry memo multiplied parent maps across the engine heap. They keep their own
        // declared properties plus the implicit project.* entries.
        Map<String, String> retainedProps = props;
        if (!"pom".equalsIgnoreCase(child.packaging())) {
            retainedProps = new LinkedHashMap<>(child.properties());
            retainedProps.put("project.groupId", groupId);
            retainedProps.put("project.artifactId", child.artifactId());
            retainedProps.put("project.version", version);
            retainedProps.put("project.packaging", child.packaging());
        }

        // A relocation belongs to the POM that declares it — it is not inherited from a parent.
        return new EffectivePom(
                groupId,
                child.artifactId(),
                version,
                child.packaging(),
                retainedProps,
                finalDeps,
                retainedManaged,
                child.relocation());
    }

    // --- merge helpers -----------------------------------------------------

    /**
     * Expand unique {@code import}-scoped BOM POMs keyed by GAV. One unique BOM stays on the caller
     * thread; several run on {@link JkThreads#io} with independent cycle sets.
     */
    private Map<String, EffectivePom> buildBomImportsParallel(
            List<Coordinate> bomCoords, Set<String> visiting, int depth) throws IOException, InterruptedException {
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
        Map<String, CompletableFuture<EffectivePom>> futures = new LinkedHashMap<>();
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
        Map<String, EffectivePom> out = new LinkedHashMap<>();
        Thread self = Thread.currentThread();
        try {
            joinExpansions(unique, futures, out, self);
        } finally {
            // Quiesce before returning OR throwing: an expansion future this thread broke away
            // from (deadlock detected, bound elapsed, sibling threw) keeps running on the pool
            // and writes into the repo cache after the caller has moved on — test @TempDir
            // cleanup raced exactly that (JK-2153). Workers on a real cycle fail fast via
            // joinWouldDeadlock, so this drain is short; the bound is a backstop.
            long drainDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(JOIN_FALLBACK_MS);
            for (CompletableFuture<EffectivePom> f : futures.values()) {
                if (f.isDone()) continue;
                long leftMs = TimeUnit.NANOSECONDS.toMillis(drainDeadline - System.nanoTime());
                if (leftMs <= 0) break;
                try {
                    f.get(leftMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException | TimeoutException ignored) {
                    // outcome irrelevant — only quiescence matters here
                }
            }
        }
        return out;
    }

    private void joinExpansions(
            LinkedHashMap<String, Coordinate> unique,
            Map<String, CompletableFuture<EffectivePom>> futures,
            Map<String, EffectivePom> out,
            Thread self)
            throws IOException, InterruptedException {
        for (var e : futures.entrySet()) {
            // Register the join in the waits-for graph: a worker whose own await chain
            // reaches this thread could not see joins through these futures, so neither side
            // detected the cycle — the worker parked for the full JOIN_FALLBACK_MS while this
            // thread sat in an unbounded join(). With the edge recorded, the worker's
            // joinWouldDeadlock fires immediately; and if WE detect the loop (or the bound
            // elapses), the entry is simply left out and merge()'s serial-expand fallback builds
            // the BOM in-line with this thread's visiting set — a real POM cycle then throws the
            // loud cycle diagnostic.
            String bomKey = processKey(unique.get(e.getKey()));
            WAITING_ON.put(self, bomKey);
            try {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(JOIN_FALLBACK_MS);
                while (true) {
                    try {
                        out.put(e.getKey(), e.getValue().get(JOIN_POLL_MS, TimeUnit.MILLISECONDS));
                        break;
                    } catch (TimeoutException te) {
                        Flight f = IN_FLIGHT.get(bomKey);
                        if (f != null && f.owner() != self && joinWouldDeadlock(self, f)) break;
                        if (System.nanoTime() - deadline > 0) break;
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
            } finally {
                WAITING_ON.remove(self);
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
    private static String depKey(Pom.Dep dep) {
        String type = blank(dep.type()) ? "jar" : dep.type();
        String classifier = blank(dep.classifier()) ? "" : dep.classifier();
        return dep.module() + ":" + type + ":" + classifier;
    }

    private static List<Pom.Dep> substituteAll(List<Pom.Dep> deps, Map<String, String> props) {
        List<Pom.Dep> out = new ArrayList<>(deps.size());
        for (Pom.Dep d : deps) {
            out.add(new Pom.Dep(
                    substitute(d.groupId(), props),
                    substitute(d.artifactId(), props),
                    substitute(d.version(), props),
                    substitute(d.scope(), props),
                    d.optional(),
                    substitute(d.classifier(), props),
                    substitute(d.type(), props),
                    d.exclusions()));
        }
        return out;
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

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String substitute(String raw, Map<String, String> ctx) {
        if (raw == null) return null;
        // Iterate up to a small fixed budget to resolve chained refs like ${a} → ${b} → "x".
        String current = raw;
        for (int pass = 0; pass < 4; pass++) {
            Matcher m = PROPERTY_REF.matcher(current);
            if (!m.find()) return current;
            m.reset();
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String key = m.group(1);
                String value = ctx.get(key);
                if (value != null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(value));
                    changed = true;
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                }
            }
            m.appendTail(sb);
            current = sb.toString();
            if (!changed) return current;
        }
        return current;
    }
}
