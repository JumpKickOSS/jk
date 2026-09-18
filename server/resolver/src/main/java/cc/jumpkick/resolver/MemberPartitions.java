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
import java.util.TreeSet;

/**
 * The rows a workspace member reads instead of the merged solve's. The merged manifest is solved
 * once and its rows are the workspace's answer; a member is solved on its own only when that
 * answer cannot be its answer: it declares an exact version the merged row does not carry, a BOM
 * of its own table that not every member holds manages a coordinate in its closure at a version
 * the merged row does not carry, or a coordinate in its closure was pinned by a BOM the member
 * does not hold at a version an edge of the member's own graph cannot take — its own platform
 * table manages the module at another version, or a dependency's POM declared one the pinned
 * version is below or past the compatible line of. A floating selector and a compatible lift are
 * floors the workspace's row satisfies. A member is also solved on its own when a BOM or entry of
 * its table that not every member holds, or an {@code exclude} list on a root of its own that the
 * merged root lacks, excludes an edge the merged rows carry under that root; its row is then the
 * merged version without that edge.
 * Where the member's solve disagrees with a merged row — its version, its scopes or the edges it
 * keeps — its row is added with {@code members = [path]}; where it agrees, nothing is added. A merged row a member's own table manages at the
 * merged version, through a BOM or entry the workspace's table never folded, carries that
 * provenance as {@code pinned-by}: the version is what the holder's BOM says, and the lock says so
 * for every member that reads the row. See {@code docs/user/workspaces.md}.
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
        // name@version → the scope groups and edges the merged rows at that version carry.
        Map<String, UnionRow> unionRows = new HashMap<>();
        for (Lockfile.Artifact row : merged.artifacts()) {
            UnionRow union = unionRows.computeIfAbsent(
                    row.packageKey() + "@" + row.version(), k -> new UnionRow(new HashSet<>(), new HashSet<>()));
            union.groups().addAll(groupsOf(row));
            union.depKeys().addAll(depKeys(row));
        }

        // name@version → the partition row and the members that read it.
        Map<String, Lockfile.Artifact> partitions = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> partitionMembers = new LinkedHashMap<>();
        Map<String, EnumMap<Scope, Boolean>> partitionScopes = new LinkedHashMap<>();
        // name@version → the provenance a holder's table lends a merged row it agrees with.
        Map<String, String> carried = new LinkedHashMap<>();
        List<Flagged> flaggedMembers = flaggedMembers(members, merged, carried);
        int solved = 0;
        for (Flagged flaggedMember : flaggedMembers) {
            LockOrchestrator.Member member = flaggedMember.member();
            JkBuild manifest = flaggedMember.manifest();
            PlatformConstraints own = flaggedMember.own();
            // Each flagged member costs a solve of its own; on a cold large reactor that is where
            // the lock's time goes, so the label says which member and how many remain.
            observer.onPhase(passLabel(member.path(), ++solved, flaggedMembers.size()));
            Map<String, String> prefs =
                    prefsFor(flaggedMember.flagged(), memberPrefs.getOrDefault(member.path(), Map.of()));
            // The table read to flag the member is the table its solve runs under.
            Lockfile mine = solver.solve(manifest, featuresFor(manifest), prefs, own);
            Map<String, String> differing = new TreeMap<>();
            Map<String, Set<String>> pruned = new HashMap<>();
            // The BOM or entry of the member's own table that pins each differing row's version, by
            // row identity; a row its own table does not pin at that version has no entry.
            Map<String, String> ownPinned = new HashMap<>();
            // Every module the member's own solve carries: an edge onto one of these at another
            // version is moved, not pruned.
            Set<String> mineModules = new HashSet<>();
            for (Lockfile.Artifact row : mine.artifacts())
                mineModules.add(PackageId.parse(row.packageKey()).ga());
            for (Lockfile.Artifact row : mine.artifacts()) {
                String key = row.packageKey() + "@" + row.version();
                UnionRow union = unionRows.get(key);
                Set<String> keys = depKeys(row);
                if (union != null && union.groups().containsAll(groupsOf(row)) && keys.containsAll(union.depKeys())) {
                    continue;
                }
                if (union != null) {
                    Set<String> lacking = new TreeSet<>();
                    for (String dep : union.depKeys()) {
                        String ga = PackageId.parse(dep).ga();
                        if (!keys.contains(dep) && !mineModules.contains(ga)) lacking.add(ga);
                    }
                    pruned.put(row.displayIdentity(), lacking);
                }
                partitions.putIfAbsent(key, row);
                partitionMembers
                        .computeIfAbsent(key, k -> new LinkedHashSet<>())
                        .add(member.path());
                EnumMap<Scope, Boolean> scopes = partitionScopes.computeIfAbsent(key, k -> new EnumMap<>(Scope.class));
                for (Scope scope : row.scopes()) scopes.put(scope, Boolean.TRUE);
                differing.put(row.displayIdentity(), row.version());
                String pinner = own.pinnedBy(PackageId.parse(row.packageKey()).ga(), row.version());
                if (pinner != null) ownPinned.put(row.displayIdentity(), pinner);
            }
            if (!differing.isEmpty()) observer.onNote(note(member.path(), differing, pruned, ownPinned, merged));
        }
        if (partitions.isEmpty() && carried.isEmpty()) return merged;
        List<Lockfile.Artifact> rows = new ArrayList<>(merged.artifacts().size() + partitions.size());
        for (Lockfile.Artifact row : merged.artifacts()) {
            String by = carried.get(row.packageKey() + "@" + row.version());
            rows.add(by == null ? row : row.withPinnedBy(by));
        }
        for (Map.Entry<String, Lockfile.Artifact> e : partitions.entrySet()) {
            Lockfile.Artifact row = e.getValue()
                    .withScopes(new ArrayList<>(Objects.requireNonNull(partitionScopes.get(e.getKey()))
                            .keySet()))
                    .withMembers(new ArrayList<>(Objects.requireNonNull(partitionMembers.get(e.getKey()))));
            rows.add(row);
        }
        return merged.withArtifacts(rows);
    }

    /** A member whose own platform table or pins flag it for a solve of its own, with what the flagging read. */
    private record Flagged(
            LockOrchestrator.Member member, JkBuild manifest, PlatformConstraints own, Set<String> flagged) {}

    /**
     * The members that need a solve of their own, in workspace order — counted before the first solve
     * runs; each holder's provenance is carried onto the merged rows it agrees with on the way.
     */
    private List<Flagged> flaggedMembers(
            List<LockOrchestrator.Member> members, Lockfile merged, Map<String, String> carried)
            throws IOException, InterruptedException {
        List<Flagged> out = new ArrayList<>();
        for (LockOrchestrator.Member member : members) {
            JkBuild manifest = solvable(member.manifest());
            PlatformConstraints own = PlatformConstraints.collect(manifest, repos, pomBuilder, bomTables, pinPolicy);
            Reach reach = reach(manifest);
            carryProvenance(reach, own, merged, carried);
            Set<String> flagged = flagged(reach, own);
            if (flagged.isEmpty() && !prunes(reach, own)) continue;
            out.add(new Flagged(member, manifest, own, flagged));
        }
        return out;
    }

    /** The phase label of one member's solve: {@code Solving members on their own… 2 of 7: services/api}. */
    static String passLabel(String memberPath, int index, int total) {
        return "Solving members on their own… " + index + " of " + total + ": " + memberPath;
    }

    /**
     * What one member's graph is in the merged solve: its declared roots per graph, every root, and
     * every package key the merged solve's edges reach from them.
     */
    private record Reach(LockRoots.Declared declared, List<Dependency> roots, Set<String> closure) {}

    private Reach reach(JkBuild manifest) {
        LockRoots.Declared declared = LockRoots.partition(manifest, featuresFor(manifest), withDefaults);
        List<Dependency> roots = new ArrayList<>();
        roots.addAll(declared.main().values());
        roots.addAll(declared.test().values());
        roots.addAll(declared.processor().values());
        return new Reach(declared, roots, closure(roots));
    }

    /**
     * Lend a merged row in the member's reach the provenance of the member's own table where that
     * table manages the row's module at the row's version and the row carries none: the workspace's
     * solve ran without the member's BOM, yet the version is what the BOM says. The first member in
     * workspace order to say so is the one the row names. A module the member pins exactly is the
     * pin's, as it is in the member's own solve, and lends nothing.
     */
    private static void carryProvenance(
            Reach reach, PlatformConstraints own, Lockfile merged, Map<String, String> carried) {
        Set<String> pinnedExactly = new HashSet<>();
        for (Dependency root : reach.roots()) {
            if (!root.isPlatformManaged() && root.version() instanceof VersionSelector.Exact) {
                pinnedExactly.add(PackageId.parse(root.packageKey()).ga());
            }
        }
        for (Lockfile.Artifact row : merged.artifacts()) {
            if (row.isPartition() || !reach.closure().contains(row.packageKey())) continue;
            if (row.pinnedBy() != null && !row.pinnedBy().startsWith("features:")) continue;
            String ga = PackageId.parse(row.packageKey()).ga();
            if (pinnedExactly.contains(ga)) continue;
            String by = own.pinnedBy(ga, row.version());
            if (by != null) carried.putIfAbsent(row.packageKey() + "@" + row.version(), by);
        }
    }

    /** What the merged rows at one {@code name@version} carry: their scope groups and the packages they edge onto. */
    private record UnionRow(Set<LockRoots.GraphGroup> groups, Set<String> depKeys) {}

    /** The package keys a row edges onto, whatever version each edge names. */
    private static Set<String> depKeys(Lockfile.Artifact row) {
        Set<String> keys = new HashSet<>();
        for (String ref : row.deps()) {
            int at = ref.indexOf('@');
            keys.add(at > 0 ? ref.substring(0, at) : ref);
        }
        return keys;
    }

    /**
     * True when the member excludes an edge the merged solve kept in the member's reach, and the
     * merged solve did not: a root the member declares carries an {@code exclude} list the merged
     * root of that package lacks — the first declaration of a package is the merged manifest's, a
     * later member's list never reaches it — or a BOM of the member's table writes exclusions on a
     * root the member declares without a list of its own, or a {@code [managed-dependencies]} entry
     * of the table writes them on a module in the closure, and the workspace's table — solved under
     * what every member holds — does not write the same pattern. An exclusion prunes the whole
     * subtree under the edge that carries it, as Maven's does, so the merged rows are walked from
     * that root or module down.
     */
    private boolean prunes(Reach reach, PlatformConstraints own) {
        PlatformConstraints shared = union.constraints();
        Map<String, Dependency> mergedRoots = mergedRootsByKey();
        for (Dependency root : reach.roots()) {
            if (root.isWorkspace() || root.isGit() || root.isPath()) continue;
            Set<String> patterns = new LinkedHashSet<>();
            if (root.exclusions().isEmpty()) {
                patterns.addAll(own.bomExclusions(root.module()));
                shared.bomExclusions(root.module()).forEach(patterns::remove);
            } else {
                patterns.addAll(root.exclusions());
                Dependency merged = mergedRoots.get(root.packageKey());
                if (merged != null) merged.exclusions().forEach(patterns::remove);
            }
            if (!patterns.isEmpty() && keepsExcludedEdgeUnder(root.packageKey(), patterns)) return true;
        }
        for (Map.Entry<String, Map<String, Set<String>>> e :
                own.managedExclusions().entrySet()) {
            Set<String> patterns = new LinkedHashSet<>(e.getValue().keySet());
            Map<String, Set<String>> sharedPatterns = shared.managedExclusions().get(e.getKey());
            if (sharedPatterns != null) patterns.removeAll(sharedPatterns.keySet());
            if (patterns.isEmpty()) continue;
            for (String key : reach.closure()) {
                if (PackageId.parse(key).ga().equals(e.getKey()) && keepsExcludedEdgeUnder(key, patterns)) return true;
            }
        }
        return false;
    }

    /** The merged manifest's roots as the merged solve read them, by package key. */
    private Map<String, Dependency> mergedRootsByKey() {
        Map<String, Dependency> byKey = new HashMap<>();
        LockRoots.Roots roots = union.roots();
        for (List<Dependency> graph : List.of(roots.main(), roots.test(), roots.processor())) {
            for (Dependency root : graph) byKey.putIfAbsent(root.packageKey(), root);
        }
        return byKey;
    }

    /**
     * True when a merged module at or below {@code key} edges onto a package one of {@code
     * patterns} covers — the edge the member's own solve prunes under that key.
     */
    private boolean keepsExcludedEdgeUnder(String key, Set<String> patterns) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(key);
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (!visited.add(next)) continue;
            Resolution.ResolvedModule merged = unionByKey.get(next);
            if (merged == null) continue;
            for (String ref : merged.deps()) {
                int at = ref.indexOf('@');
                String dep = at > 0 ? ref.substring(0, at) : ref;
                if (ExclusionLedger.isExcluded(dep, patterns)) return true;
                queue.add(dep);
            }
        }
        return false;
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
    private Set<String> flagged(Reach reach, PlatformConstraints own) {
        Set<String> flagged = new LinkedHashSet<>();
        LockRoots.Declared declared = reach.declared();
        Set<String> mainClosure = closure(new ArrayList<>(declared.main().values()));
        for (Dependency root : reach.roots()) {
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
        Set<String> closure = reach.closure();
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
    static JkBuild solvable(JkBuild manifest) {
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

    /**
     * One line per member: which coordinates it reads its own rows for, and what the workspace has —
     * its version, or, at the same version, the edges the member's row lacks or has at versions of
     * its own. A member whose own rows are every one pinned by a BOM or entry of its own table — the
     * shape a member-held BOM documents, its versions and its exclusions alike — is one sentence
     * naming the count and the pinning tables, since the rows say what its table says and name
     * nothing to act on.
     */
    private static String note(
            String path,
            Map<String, String> differing,
            Map<String, Set<String>> pruned,
            Map<String, String> ownPinned,
            Lockfile merged) {
        Map<String, String> mergedVersions = new HashMap<>();
        for (Lockfile.Artifact row : merged.artifacts())
            mergedVersions.putIfAbsent(row.displayIdentity(), row.version());
        if (ownPinned.keySet().containsAll(differing.keySet())) {
            // A BOM is named once: the provenance of a family it aligns carries the family in
            // parentheses after the BOM, and the sentence names the BOM.
            Set<String> pinners = new LinkedHashSet<>();
            for (String pinner : ownPinned.values()) pinners.add(pinner.replaceFirst(" \\(.*\\)$", ""));
            int n = differing.size();
            return path + " reads its own rows for " + n + (n == 1 ? " coordinate " : " coordinates ")
                    + String.join(", ", pinners) + (pinners.size() == 1 ? " manages" : " manage");
        }
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
            Set<String> lacking = pruned.get(e.getKey());
            if (workspace == null) {
                out.append(" (only there)");
            } else if (workspace.equals(e.getValue()) && lacking != null && !lacking.isEmpty()) {
                out.append(" (the workspace's without ")
                        .append(String.join(", ", lacking))
                        .append(')');
            } else if (workspace.equals(e.getValue())) {
                out.append(" (the workspace's, its edges at this member's versions)");
            } else {
                out.append(" (workspace ").append(workspace).append(')');
            }
            named++;
        }
        return out.toString();
    }
}
