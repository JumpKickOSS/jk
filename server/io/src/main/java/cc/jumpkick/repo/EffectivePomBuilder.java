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

    public EffectivePomBuilder(MavenRepo repo) {
        this(RepoGroup.of(repo));
    }

    public EffectivePomBuilder(RepoGroup repos) {
        this.repos = Objects.requireNonNull(repos, "repos");
    }

    /**
     * Build (or return cached) effective POM. Concurrent-safe via {@link ConcurrentHashMap} cache;
     * each call uses its own cycle-detection set so sibling BOM imports can expand in parallel.
     */
    public EffectivePom build(Coordinate coord) throws IOException, InterruptedException {
        String key = coord.toGav();
        EffectivePom hit = cache.get(key);
        if (hit != null) return hit;
        return buildInternal(coord, new HashSet<>(), 0);
    }

    private EffectivePom buildInternal(Coordinate coord, Set<String> visiting, int depth)
            throws IOException, InterruptedException {
        if (depth > MAX_DEPTH) {
            throw new PomParseException("POM parent / BOM chain deeper than " + MAX_DEPTH + " at " + coord);
        }
        String key = coord.toGav();
        EffectivePom cached = cache.get(key);
        if (cached != null) return cached;
        if (!visiting.add(key)) {
            throw new PomParseException("cycle in POM chain at " + key + " (already visiting: " + visiting + ")");
        }
        try {
            RepoGroup.RepoFetched hit = repos.tryFetchPom(coord)
                    .orElseThrow(() ->
                            new MavenRepo.ArtifactNotFoundException("POM not found in any declared repo: " + coord));
            Pom raw = PomParser.parse(Files.readAllBytes(hit.fetched().cachePath()));
            EffectivePom effective = merge(raw, visiting, depth);
            cache.put(key, effective);
            return effective;
        } finally {
            visiting.remove(key);
        }
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

        return new EffectivePom(
                groupId, child.artifactId(), version, child.packaging(), props, finalDeps, retainedManaged);
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
        for (var e : futures.entrySet()) {
            try {
                out.put(e.getKey(), e.getValue().join());
            } catch (CompletionException ex) {
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
        return out;
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
