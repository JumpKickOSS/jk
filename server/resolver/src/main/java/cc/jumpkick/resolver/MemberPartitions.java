// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.PinPolicy;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The rows a workspace member reads instead of the merged solve's. The merged manifest is solved
 * once and its rows are the workspace's answer; a member is solved on its own only when that
 * answer cannot be its answer: it declares an exact version the merged row does not carry, a BOM
 * of its own table that not every member holds manages a coordinate in its closure at a version
 * the merged row does not carry, or a coordinate in its closure was pinned by a BOM the member
 * does not hold at a version an edge of the member's own graph cannot take — its own platform
 * table manages the module at another version, or a dependency's POM declared one the pinned
 * version is below or past the compatible line of. A floating selector and a compatible lift are
 * floors the workspace's row satisfies.
 * Where the member's solve disagrees with a merged row, its row is added with {@code members =
 * [path]}; where it agrees, nothing is added. See {@code docs/user/workspaces.md}.
 */
final class MemberPartitions {

    /**
     * Solves one member's effective manifest under the requested features it declares, the given
     * soft preferences and its own platform table, and assembles its rows.
     */
    interface MemberSolver {
        Lockfile solve(
                JkBuild manifest, Collection<String> features, Map<String, String> prefs, PlatformConstraints own)
                throws IOException, InterruptedException;
    }

    /** How many partitioned coordinates one member's note names before counting the rest. */
    private static final int NOTE_COORDINATES = 6;

    private final LockOrchestrator.Solve union;
    private final RepoGroup repos;
    private final EffectivePomBuilder pomBuilder;
    private final PlatformConstraints.BomTables bomTables;
    private final PinPolicy pinPolicy;
    private final Collection<String> featuresRequested;
    private final boolean withDefaults;

    /** package key → the merged solve's module, main graph first. */
    private final Map<String, Resolution.ResolvedModule> unionByKey = new LinkedHashMap<>();

    MemberPartitions(
            LockOrchestrator.Solve union,
            RepoGroup repos,
            EffectivePomBuilder pomBuilder,
            PlatformConstraints.BomTables bomTables,
            PinPolicy pinPolicy,
            Collection<String> featuresRequested,
            boolean withDefaults) {
        this.union = union;
        this.repos = repos;
        this.pomBuilder = pomBuilder;
        this.bomTables = bomTables;
        this.pinPolicy = pinPolicy;
        this.featuresRequested = featuresRequested;
        this.withDefaults = withDefaults;
        for (Resolution graph : List.of(
                union.solved().main(), union.solved().test(), union.solved().processor())) {
            for (Resolution.ResolvedModule mod : graph.modules().values()) unionByKey.putIfAbsent(mod.module(), mod);
        }
    }

    /**
     * {@code merged} plus one partition row per coordinate a member's own solve answers differently,
     * with one note per such member. {@code memberPrefs} are the versions the previous lock's
     * partition rows held for each member, kept like any other pin.
     */
    Lockfile apply(
            Lockfile merged,
            List<LockOrchestrator.Member> members,
            Map<String, Map<String, String>> memberPrefs,
            MemberSolver solver,
            ResolveObserver observer)
            throws IOException, InterruptedException {
        // name@version → the scope groups the merged rows at that version carry.
        Map<String, Set<LockRoots.GraphGroup>> unionRows = new HashMap<>();
        for (Lockfile.Artifact row : merged.artifacts()) {
            unionRows
                    .computeIfAbsent(row.packageKey() + "@" + row.version(), k -> new HashSet<>())
                    .addAll(groupsOf(row));
        }

        // name@version → the partition row and the members that read it.
        Map<String, Lockfile.Artifact> partitions = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> partitionMembers = new LinkedHashMap<>();
        Map<String, EnumMap<Scope, Boolean>> partitionScopes = new LinkedHashMap<>();
        for (LockOrchestrator.Member member : members) {
            JkBuild manifest = solvable(member.manifest());
            PlatformConstraints own = PlatformConstraints.collect(manifest, repos, pomBuilder, bomTables, pinPolicy);
            Set<String> flagged = flagged(manifest, own);
            if (flagged.isEmpty()) continue;
            Map<String, String> prefs = prefsFor(flagged, memberPrefs.getOrDefault(member.path(), Map.of()));
            // The table read to flag the member is the table its solve runs under.
            Lockfile mine = solver.solve(manifest, featuresFor(manifest), prefs, own);
            Map<String, String> differing = new TreeMap<>();
            for (Lockfile.Artifact row : mine.artifacts()) {
                String key = row.packageKey() + "@" + row.version();
                if (unionRows.getOrDefault(key, Set.of()).containsAll(groupsOf(row))) continue;
                partitions.putIfAbsent(key, row);
                partitionMembers
                        .computeIfAbsent(key, k -> new LinkedHashSet<>())
                        .add(member.path());
                EnumMap<Scope, Boolean> scopes = partitionScopes.computeIfAbsent(key, k -> new EnumMap<>(Scope.class));
                for (Scope scope : row.scopes()) scopes.put(scope, Boolean.TRUE);
                differing.put(row.displayIdentity(), row.version());
            }
            if (!differing.isEmpty()) observer.onNote(note(member.path(), differing, merged));
        }
        if (partitions.isEmpty()) return merged;
        List<Lockfile.Artifact> rows = new ArrayList<>(merged.artifacts());
        for (Map.Entry<String, Lockfile.Artifact> e : partitions.entrySet()) {
            Lockfile.Artifact row = e.getValue()
                    .withScopes(new ArrayList<>(Objects.requireNonNull(partitionScopes.get(e.getKey()))
                            .keySet()))
                    .withMembers(new ArrayList<>(Objects.requireNonNull(partitionMembers.get(e.getKey()))));
            rows.add(row);
        }
        return merged.withArtifacts(rows);
    }

    /**
     * The graphs a row's scopes belong to. A member row agrees with the workspace only where a merged
     * row at that version carries each of them: a version the workspace holds as a test-only dual is
     * not on a member's main classpath, so a member whose main graph wants it reads a row of its own.
     */
    private static Set<LockRoots.GraphGroup> groupsOf(Lockfile.Artifact row) {
        Set<LockRoots.GraphGroup> groups = EnumSet.noneOf(LockRoots.GraphGroup.class);
        for (Scope scope : row.scopes()) groups.add(LockRoots.graphGroup(scope));
        return groups;
    }

    /**
     * The {@code group:artifact}s on which the merged answer cannot be this member's: an exact pin of
     * the member the merged version does not equal, a module the member's own table manages through
     * a BOM the workspace's table never folded at a version the merged row does not carry, or a
     * merged version a BOM the member does not hold pinned while the member's own platform table
     * manages the module at another version or an edge in the member's closure declared a version
     * the pinned one cannot stand in for. A root the
     * member asks for with a floating selector takes the merged row whatever it is, and so does a
     * test-scope exact pin on a module the member's main graph reaches: main's version is the one
     * on its test classpath, and a solve of its own would say the same. Empty means the member
     * reads the merged rows as they are.
     */
    private Set<String> flagged(JkBuild manifest, PlatformConstraints own) {
        Set<String> flagged = new LinkedHashSet<>();
        LockRoots.Declared declared = LockRoots.partition(manifest, featuresFor(manifest), withDefaults);
        Set<String> mainClosure = closure(new ArrayList<>(declared.main().values()));
        List<Dependency> roots = new ArrayList<>();
        roots.addAll(declared.main().values());
        roots.addAll(declared.test().values());
        roots.addAll(declared.processor().values());
        for (Dependency root : roots) {
            Resolution.ResolvedModule merged = unionByKey.get(root.packageKey());
            if (merged == null || root.isPlatformManaged()) continue;
            if (declared.test().containsKey(root.packageKey())
                    && !declared.main().containsKey(root.packageKey())
                    && mainClosure.contains(root.packageKey())) {
                continue;
            }
            if (root.version() instanceof VersionSelector.Exact exact
                    && !exact.version().equals(merged.version())) {
                flagged.add(PackageId.parse(root.packageKey()).ga());
            }
        }
        Set<String> closure = closure(roots);
        for (String key : closure) {
            Resolution.ResolvedModule merged = unionByKey.get(key);
            if (merged == null) continue;
            String ga = PackageId.parse(key).ga();
            String ownManaged = own.versions().get(ga);
            if (merged.version().equals(ownManaged)) continue;
            if (union.constraints().pinnedBy(ga, merged.version()) != null) {
                // A BOM the member does not hold pinned the merged version.
                if (ownManaged != null || edgeDeclaresOutside(key, merged.version(), closure)) flagged.add(ga);
            } else if (ownManaged != null
                    && !ownManaged.equals(union.constraints().collectedVersion(ga))) {
                // A BOM the workspace's table never folded — the member's own, or a sibling's it depends
                // on — manages the module at a version the merged row does not carry.
                flagged.add(ga);
            }
        }
        return flagged;
    }

    /**
     * The requested features {@code manifest} declares. A name the root has and a member lacks is not
     * the member's to activate, so it stays out of that member's own solve.
     */
    private Collection<String> featuresFor(JkBuild manifest) {
        List<String> mine = new ArrayList<>();
        for (String name : featuresRequested) {
            if (manifest.features().byName().containsKey(name)) mine.add(name);
        }
        return mine;
    }

    /**
     * True when an edge inside the member's closure declares {@code key} at something {@code version}
     * cannot stand in for: a range it is outside, or a plain version it is below or past the
     * compatible line of. A lift within the line is a floor the pinned version satisfies, as a
     * sibling's higher edge is.
     */
    private boolean edgeDeclaresOutside(String key, String version, Set<String> closure) {
        String ref = key + "@" + version;
        for (String parentKey : closure) {
            Resolution.ResolvedModule parent = unionByKey.get(parentKey);
            if (parent == null || !parent.deps().contains(ref)) continue;
            String declared = parent.declared().get(ref);
            if (declared != null && !satisfies(declared, version)) return true;
        }
        return false;
    }

    /**
     * Whether {@code version} satisfies a POM edge's {@code declared} version: a range as it reads,
     * a plain version as the floor of its compatible line ({@code ^declared}). A declaration the
     * grammar cannot read is satisfied by itself alone.
     */
    private static boolean satisfies(String declared, String version) {
        if (declared.equals(version)) return true;
        try {
            VersionSet accepted = VersionSelectors.looksLikeMavenRange(declared)
                    ? VersionSelectors.parseRange(declared)
                    : VersionSelectors.caretRange(declared);
            return accepted.contains(version);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Every package key the member reaches through the merged solve's edges. */
    private Set<String> closure(List<Dependency> roots) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (Dependency root : roots) queue.add(root.packageKey());
        while (!queue.isEmpty()) {
            String key = queue.poll();
            if (!visited.add(key)) continue;
            Resolution.ResolvedModule mod = unionByKey.get(key);
            if (mod == null) continue;
            for (String ref : mod.deps()) {
                int at = ref.indexOf('@');
                queue.add(at > 0 ? ref.substring(0, at) : ref);
            }
        }
        return visited;
    }

    /**
     * The merged solve's decisions as soft preferences for a member's own solve, minus the flagged
     * coordinates, under the versions the previous lock's partition rows held for this member.
     */
    private Map<String, String> prefsFor(Set<String> flagged, Map<String, String> previous) {
        Map<String, String> prefs = new HashMap<>();
        for (Resolution.ResolvedModule mod : unionByKey.values()) {
            String ga = PackageId.parse(mod.module()).ga();
            if (flagged.contains(ga)) continue;
            prefs.putIfAbsent(mod.module(), mod.version());
            prefs.putIfAbsent(ga, mod.version());
        }
        prefs.putAll(previous);
        return prefs;
    }

    /** A member's manifest without the roots a member solve cannot resolve: git and path sources. */
    private static JkBuild solvable(JkBuild manifest) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        boolean dropped = false;
        for (Map.Entry<Scope, List<Dependency>> e :
                manifest.dependencies().byScope().entrySet()) {
            List<Dependency> kept = new ArrayList<>();
            for (Dependency d : e.getValue()) {
                if (d.isGit() || d.isPath() || d.isWorkspace()) {
                    dropped = true;
                    continue;
                }
                kept.add(d);
            }
            if (!kept.isEmpty()) byScope.put(e.getKey(), kept);
        }
        return dropped ? manifest.withDependencies(new JkBuild.Dependencies(byScope)) : manifest;
    }

    /** One line per member: which coordinates it reads its own rows for, and what the workspace has. */
    private static String note(String path, Map<String, String> differing, Lockfile merged) {
        Map<String, String> mergedVersions = new HashMap<>();
        for (Lockfile.Artifact row : merged.artifacts())
            mergedVersions.putIfAbsent(row.displayIdentity(), row.version());
        StringBuilder out = new StringBuilder(path)
                .append(" reads its own rows for ")
                .append(differing.size())
                .append(differing.size() == 1 ? " coordinate: " : " coordinates: ");
        int named = 0;
        for (Map.Entry<String, String> e : differing.entrySet()) {
            if (named == NOTE_COORDINATES) {
                out.append(", +").append(differing.size() - named).append(" more");
                break;
            }
            if (named > 0) out.append(", ");
            out.append(e.getKey()).append(' ').append(e.getValue());
            String workspace = mergedVersions.get(e.getKey());
            out.append(workspace == null ? " (only there)" : " (workspace " + workspace + ")");
            named++;
        }
        return out.toString();
    }
}
