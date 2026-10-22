// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ToolEntry;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.KmpRedirects;
import cc.jumpkick.resolver.LockOrchestrator;
import cc.jumpkick.resolver.PlatformConstraints;
import cc.jumpkick.resolver.PubGrubResolver;
import cc.jumpkick.resolver.Resolution;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A transitive step-dependency's runtime closure as a directory of jars under the CAS
 * ({@code plugin-tools/<key>}), handed to a step for {@code -cp <dir>/*}.
 *
 * <p>{@code jk lock} resolves the closure from the tool's POMs the way it resolves a module's —
 * PubGrub, under BOM pins when {@code managed-by} or {@code with} is set, a Kotlin-multiplatform
 * root redirected to its JVM target so the classpath carries classes and not a metadata jar — and
 * pins it as a closure row naming every jar, each jar a {@code [[tool]]} row of its own. A build
 * resolves nothing: it fetches the jars the closure row names, each against its digest, into a
 * staging directory that is published atomically, so a closure is whole or absent. A closure that
 * fails to materialize names the tool, the directory and the cause.
 *
 * <p>The directory's key is the pinned jars and their digests, and the directory carries a {@value
 * #LISTING} listing its jars: one that lost a jar is materialized again in place.
 */
final class ToolClosures {

    /** Every jar of the closure, one file name per line, written last into the staging directory. */
    static final String LISTING = ".closure";

    private ToolClosures() {}

    /** A closure as {@code jk lock} resolves it: its roots and BOM as resolved, and every jar. */
    record Resolved(
            Coordinate root,
            List<Coordinate> with,
            @Nullable String managedBy,
            List<Coordinate> jars) {}

    /** {@code dep}'s closure resolved from its POMs, nothing fetched but metadata: what {@code jk lock} pins. */
    static Resolved resolve(PluginContributions.StepDep dep, RepoGroup repos) throws IOException, InterruptedException {
        // Resolve floating ${config.version} segments first so the closure tracks the concrete line.
        List<Coordinate> roots = new ArrayList<>();
        roots.add(PluginBuild.resolveCoordinate(repos, dep.coordinateSpec()));
        for (String w : dep.with()) {
            roots.add(PluginBuild.resolveCoordinate(repos, w));
        }
        String managedByResolved = null;
        if (dep.managedBySpec() != null) {
            Coordinate bom = PluginBuild.resolveCoordinate(repos, dep.managedBySpec());
            managedByResolved = bom.group() + ":" + bom.artifact() + ":" + bom.version();
        }
        KmpRedirects kmp = new KmpRedirects(repos, "standard-jvm");
        Resolution resolution = resolve(roots, managedByResolved, repos, kmp);
        return new Resolved(
                roots.getFirst(), roots.subList(1, roots.size()), managedByResolved, jars(roots, resolution, kmp));
    }

    /**
     * The closure directory for {@code dep}, as the lock's closure row pins it: materialized on first
     * use and served from the CAS after. A tool without a closure row, or a jar without its own row,
     * is lock drift, refused; so is a jar whose bytes are not the pinned ones.
     */
    static Path materialize(PluginContributions.StepDep dep, RepoGroup repos, Cas cas, @Nullable Lockfile lock)
            throws IOException, InterruptedException {
        String rootSpec = Objects.requireNonNull(dep.coordinateSpec(), "coordinate");
        ToolEntry row =
                lock == null ? null : ToolEntry.closureIn(lock.tools(), rootSpec, dep.with(), dep.managedBySpec());
        if (row == null) {
            throw new IOException("jk-lock.toml pins no [[tool]] closure for " + rootSpec + " — run `jk lock`");
        }
        Map<Coordinate, String> pinned = new LinkedHashMap<>();
        for (String jar : row.closure()) {
            Coordinate coord = Coordinate.parse(jar);
            pinned.put(coord, PluginBuild.lockedSha256(coord, lock));
        }
        List<Coordinate> roots = new ArrayList<>();
        roots.add(row.root());
        for (String w : row.with()) roots.add(Coordinate.parse(w));

        Path dir = cas.root().resolve("plugin-tools").resolve(cacheKey(row.root(), pinned));
        if (complete(dir)) return dir;
        Path staging = null;
        try {
            staging = Files.createTempDirectory(Files.createDirectories(dir.getParent()), ".closure-");
            for (Map.Entry<Coordinate, String> jar : pinned.entrySet()) {
                alias(staging, jar.getKey(), PluginBuild.fetchPinned(repos, jar.getKey(), lock));
            }
            writeListing(staging);
            publish(staging, dir);
        } catch (IOException e) {
            if (staging != null) PathUtil.deleteRecursively(staging);
            throw failure(roots, dir, e);
        }
        return dir;
    }

    /** One graph over every root, BOM-aligned when a BOM manages it, KMP roots redirected. */
    private static Resolution resolve(
            List<Coordinate> roots, @Nullable String managedByResolved, RepoGroup repos, KmpRedirects kmp)
            throws IOException, InterruptedException {
        List<Dependency> declared = new ArrayList<>();
        for (Coordinate root : roots) {
            declared.add(
                    new Dependency(root.group() + ":" + root.artifact(), VersionSelector.parse("=" + root.version())));
        }
        Map<String, String> bomConstraints = Map.of();
        if (managedByResolved != null) {
            bomConstraints = loadBomConstraints(repos, managedByResolved);
        }
        return new PubGrubResolver(repos, bomConstraints, Map.of(), kmp).resolve(declared);
    }

    /**
     * Every resolved jar and every root, each GAV once per classifier: a classified jar is a jar of
     * its own beside the plain one. A multiplatform root whose JVM target is in the resolution is
     * left out: its own jar holds Kotlin metadata and no class, and the target supplies the classes.
     */
    static List<Coordinate> jars(List<Coordinate> roots, Resolution resolution, KmpRedirects kmp) {
        // Dedupe by GAV and classifier so package-id keys of two types don't list one jar twice.
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Coordinate> out = new ArrayList<>();
        for (var resolved : resolution.modules().values()) {
            Coordinate coord = resolved.coordinate();
            if (!seen.add(jarKey(coord))) continue;
            if (kmp.selectionFor(coord.module(), coord.version()).isPresent()) continue;
            out.add(coord);
        }
        // Ensure declared roots are present even if the solver key form differed.
        for (Coordinate root : roots) {
            if (!seen.add(jarKey(root))) continue;
            if (kmp.selectionFor(root.module(), root.version()).isPresent()) continue;
            out.add(root);
        }
        return out;
    }

    /** {@code group:artifact:version[:classifier]}: the identity of one jar of the closure. */
    private static String jarKey(Coordinate coord) {
        return coord.classifier() == null ? coord.toGav() : coord.toGav() + ":" + coord.classifier();
    }

    /**
     * Link (or copy) {@code jar} into {@code staging} as {@code <artifact>-<version>[-<classifier>].jar},
     * the name the repository serves it under; a second artifact of that name from another group
     * lands as {@code <group>_<artifact>-<version>[-<classifier>].jar} so neither hides the other.
     * Answers the alias.
     */
    static Path alias(Path staging, Coordinate coord, Path jar) throws IOException {
        String name = coord.artifact() + "-" + coord.version()
                + (coord.classifier() == null ? "" : "-" + coord.classifier()) + ".jar";
        Path alias = staging.resolve(name);
        if (Files.exists(alias)) {
            alias = staging.resolve(coord.group() + "_" + name);
        }
        try {
            Files.createLink(alias, jar);
        } catch (IOException | UnsupportedOperationException e) {
            Files.copy(jar, alias);
        }
        return alias;
    }

    /**
     * {@code staging}, a whole closure, becomes {@code dir}. A whole closure already at {@code dir}
     * — another module materialized the same key meanwhile — is adopted as it stands and the
     * staging discarded, whether it is seen before the move or the move lands on it; a directory
     * there but incomplete — a jar lost, or no listing — is replaced. A whole closure is never
     * deleted: a step may be running on it.
     */
    static void publish(Path staging, Path dir) throws IOException {
        if (complete(dir)) {
            PathUtil.deleteRecursively(staging);
            return;
        }
        if (Files.isDirectory(dir)) PathUtil.deleteRecursively(dir);
        try {
            AtomicWrites.publishDir(staging, dir);
        } catch (IOException e) {
            if (!complete(dir)) throw e;
            PathUtil.deleteRecursively(staging);
        }
    }

    /**
     * The failure a step reports: the tool whose closure it is, the directory it was bound for,
     * and the cause — spelled out for a filesystem exception, whose own message is only a path.
     */
    static IOException failure(List<Coordinate> roots, Path dir, IOException cause) {
        List<String> gavs = new ArrayList<>();
        for (Coordinate root : roots) gavs.add(root.toGav());
        return new IOException(
                "the runtime closure of " + String.join(", ", gavs) + " did not materialize under " + dir + ": "
                        + describe(cause),
                cause);
    }

    private static String describe(IOException cause) {
        String message = cause.getMessage();
        if (!(cause instanceof FileSystemException fs)) {
            return message == null ? cause.getClass().getSimpleName() : message;
        }
        String detail =
                switch (fs) {
                    case FileAlreadyExistsException ignored -> " already exists";
                    case NoSuchFileException ignored -> " does not exist";
                    default -> fs.getReason() == null ? "" : " (" + fs.getReason() + ")";
                };
        String what = fs.getOtherFile() == null ? fs.getFile() : fs.getFile() + " -> " + fs.getOtherFile();
        return cause.getClass().getSimpleName() + ": " + what + detail;
    }

    /**
     * True when {@code dir} holds a whole closure: its {@value #LISTING} is present and every jar
     * it lists is a non-empty file. A directory without the listing, whatever it holds, is not.
     */
    static boolean complete(Path dir) {
        Path manifest = dir.resolve(LISTING);
        if (!Files.isRegularFile(manifest)) return false;
        try {
            for (String name : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                if (name.isBlank()) continue;
                Path jar = dir.resolve(name);
                if (!Files.isRegularFile(jar) || Files.size(jar) == 0) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** List every jar staged so far into {@value #LISTING}, sorted, one per line. */
    static void writeListing(Path staging) throws IOException {
        List<String> names = new ArrayList<>();
        PathUtil.forEachRegularFile(staging, dir -> !dir.equals(staging), (file, attrs) -> {
            String name = file.getFileName().toString();
            if (name.endsWith(".jar")) names.add(name);
        });
        Collections.sort(names);
        Files.writeString(staging.resolve(LISTING), String.join("\n", names) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * The CAS dir name of a pinned closure: its root's artifact and version, readable, and a hash of
     * every jar with its digest, so two closures that differ in any byte are two directories.
     */
    static String cacheKey(Coordinate root, Map<Coordinate, String> pinned) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Coordinate, String> jar : pinned.entrySet()) lines.add(jar.getKey() + "=" + jar.getValue());
        Collections.sort(lines);
        String hash = Hashing.sha256Hex(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        return root.artifact() + "-" + root.version() + "__" + hash.substring(0, 40);
    }

    /**
     * Load {@code group:artifact → version} pins from a BOM POM (and its imported BOMs via
     * EffectivePom expansion).
     */
    private static Map<String, String> loadBomConstraints(RepoGroup repos, String bomGav)
            throws IOException, InterruptedException {
        // BOM coordinates are type=pom (default parse is jar).
        String spec = bomGav.contains("!") ? bomGav : bomGav + "!pom";
        Coordinate bom = Coordinate.parse(spec);
        EffectivePom bomPom = new EffectivePomBuilder(repos).build(bom);
        return bomConstraintsOf(bomPom, bomGav);
    }

    /**
     * The {@code group:artifact → version} pins {@code bomPom} manages, with the maven-resolver
     * family aligned by its owner — {@link LockOrchestrator#alignMavenResolverFamily},
     * the same derivation the lock path applies (the {@code maven-resolver.version} property, else
     * a managed api/impl pin), so a 2.x named-locks cannot land next to a 1.9 api on the tool
     * classpath either. The provenance map is the lock path's concern; this path discards it.
     */
    static Map<String, String> bomConstraintsOf(EffectivePom bomPom, String bomGav) throws IOException {
        Map<String, String> constraints = new LinkedHashMap<>();
        for (Pom.Dep m : bomPom.managedDependencies()) {
            if (m.version() == null || m.version().isBlank()) continue;
            constraints.putIfAbsent(m.module(), m.version());
        }
        PlatformConstraints.alignMavenResolverFamily(constraints, new HashMap<>(), bomPom, bomGav);
        if (constraints.isEmpty()) {
            throw new IOException("managed-by BOM " + bomGav + " contributed no managed dependency pins");
        }
        return constraints;
    }
}
