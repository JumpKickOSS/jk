// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.ArtifactFanOut;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.task.TransferBoard;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * Dependencies an artifact declares in a descriptor inside its jar rather than in its POM, each of
 * which joins the graph only when every artifact its own descriptor names as its condition is in the
 * closure: Quarkus's extension {@code conditional-dependencies} and {@code dependency-condition},
 * which an active plugin names as a {@link Rule}. A dependency that joins can meet another one's
 * condition and declare its own, so {@link ScopeSolves} solves again with the joined roots until no
 * more join.
 *
 * <p>Each jar is read once per lock, from the store the lock downloads it into anyway.
 */
public final class ConditionalDependencies {

    /**
     * Where an artifact's jar says what it depends on conditionally: the properties file {@code
     * descriptor}, whose {@code dependenciesKey} lists the dependencies ({@code
     * group:artifact[:classifier[:type]]:version}, space or comma separated) and whose {@code
     * conditionKey}, read from the dependency's own jar, lists the artifacts ({@code
     * group:artifact[:classifier[:type]]}) that must all be in the closure for it to join.
     */
    public record Rule(String descriptor, String dependenciesKey, String conditionKey) {
        public Rule {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(dependenciesKey, "dependenciesKey");
            Objects.requireNonNull(conditionKey, "conditionKey");
        }
    }

    /**
     * A conditional dependency whose condition holds: the package key of the module declaring it,
     * the dependency at the version the declaration names, and the condition, as {@code
     * group:artifact}s.
     */
    record Active(String declarer, Coordinate dependency, List<String> condition) {
        Active {
            condition = List.copyOf(condition);
        }

        /** The dependency's package key. */
        String module() {
            return PackageId.of(dependency.group(), dependency.artifact(), PackageId.DEFAULT_TYPE, "")
                    .key();
        }

        /**
         * The lock row's {@code added-by} line: the declaring {@code group:artifact}, then {@code
         * when} and the condition when there is one.
         */
        String reason() {
            String by = PackageId.parse(declarer).ga();
            return condition.isEmpty() ? by : by + " when " + String.join(", ", condition);
        }
    }

    /** What one jar's descriptors declare; {@link #NONE} for a jar with none or no jar at all. */
    private record Declared(List<Coordinate> dependencies, List<String> condition) {
        static final Declared NONE = new Declared(List.of(), List.of());
    }

    private final List<Rule> rules;
    private final Function<String, RepoGroup> reposFor;
    private final Map<String, String> managed;
    private final Map<Coordinate, Declared> read = new ConcurrentHashMap<>();

    /**
     * @param reposFor the group a package's jar is fetched from, by package key
     * @param managed the platform table's {@code group:artifact -> version}, which a joined
     *     dependency's version follows as every edge onto it does
     */
    ConditionalDependencies(List<Rule> rules, Function<String, RepoGroup> reposFor, Map<String, String> managed) {
        this.rules = List.copyOf(rules);
        this.reposFor = reposFor;
        this.managed = managed;
    }

    /**
     * The root {@code active} joins the graph as: pinned to the version the platform table manages,
     * else the one its declaration names. The solve reads the pin as a floor, as it reads a POM
     * edge's version.
     */
    Dependency root(Active active) {
        Coordinate dependency = active.dependency();
        String ga = dependency.group() + ":" + dependency.artifact();
        String version = managed.getOrDefault(ga, dependency.version());
        return new Dependency(ga, VersionSelector.parse("=" + version));
    }

    /**
     * Every conditional dependency of {@code closure}'s modules whose condition the closure meets,
     * in module order. A dependency that joins counts as present for the conditions read after it,
     * as it will be once the graph is solved with it; what it declares itself is read on the next
     * round, from the solved graph. {@code context} is more of the classpath the conditions are
     * judged against: the main graph, for the test graph's.
     */
    List<Active> active(Collection<Resolution.ResolvedModule> closure, Collection<Resolution.ResolvedModule> context)
            throws IOException, InterruptedException {
        Map<String, Resolution.ResolvedModule> byKey = new TreeMap<>();
        for (Resolution.ResolvedModule m : closure) byKey.put(m.module(), m);
        Set<String> present = new LinkedHashSet<>();
        for (Resolution.ResolvedModule m : context) present.addAll(identities(m.module()));
        for (Resolution.ResolvedModule m : closure) present.addAll(identities(m.module()));

        List<Coordinate> jars = new ArrayList<>();
        Map<Coordinate, String> owners = new LinkedHashMap<>();
        for (Resolution.ResolvedModule m : byKey.values()) {
            Coordinate jar = plainJar(m.module(), m.version());
            if (jar != null) {
                jars.add(jar);
                owners.put(jar, m.module());
            }
        }
        readAll(jars, owners);
        List<Coordinate> dependencies = new ArrayList<>();
        for (Coordinate jar : jars) dependencies.addAll(declared(jar).dependencies());
        readAll(dependencies, Map.of());

        List<Active> out = new ArrayList<>();
        Set<String> joined = new LinkedHashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Coordinate jar : jars) {
                String declarer = Objects.requireNonNull(owners.get(jar));
                for (Coordinate dependency : declared(jar).dependencies()) {
                    String key = declarer + " " + dependency.group() + ":" + dependency.artifact();
                    if (joined.contains(key)) continue;
                    List<String> condition = declared(dependency).condition();
                    if (!present.containsAll(condition)) continue;
                    joined.add(key);
                    out.add(new Active(declarer, dependency, condition));
                    if (present.add(dependency.group() + ":" + dependency.artifact())) grew = true;
                }
            }
        }
        return out;
    }

    /** The names a condition can call module {@code key} by: its {@code group:artifact}, and its package key. */
    private static List<String> identities(String key) {
        if (!PackageId.isMavenPackageKey(key)) return List.of(key);
        PackageId id = PackageId.parse(key);
        return id.classifier().isEmpty() ? List.of(id.ga()) : List.of(id.ga() + ":" + id.classifier());
    }

    /** The plain jar of {@code key} at {@code version}, or null for a module that is not one. */
    private static @Nullable Coordinate plainJar(String key, String version) {
        if (!PackageId.isMavenPackageKey(key)) return null;
        PackageId id = PackageId.parse(key);
        if (!id.classifier().isEmpty() || !PackageId.DEFAULT_TYPE.equals(id.type())) return null;
        return Coordinate.of(id.group(), id.artifact(), version);
    }

    private Declared declared(Coordinate jar) {
        return read.getOrDefault(jar, Declared.NONE);
    }

    /**
     * Read the descriptors of every jar of {@code jars} not read yet, the fetches at once; {@code
     * owners} names the package key a jar's repositories are chosen by, the coordinate's own when
     * absent.
     */
    private void readAll(List<Coordinate> jars, Map<Coordinate, String> owners)
            throws IOException, InterruptedException {
        List<Coordinate> missing = new ArrayList<>();
        for (Coordinate jar : jars) {
            if (!read.containsKey(jar) && !missing.contains(jar)) missing.add(jar);
        }
        if (missing.isEmpty()) return;
        TransferBoard board = SessionContext.current().io().transfers();
        List<ArtifactFanOut.Fetch<Map.Entry<Coordinate, Optional<RepoGroup.RepoFetched>>>> fetches = new ArrayList<>();
        for (Coordinate jar : missing) {
            String owner = owners.getOrDefault(
                    jar,
                    PackageId.of(jar.group(), jar.artifact(), PackageId.DEFAULT_TYPE, "")
                            .key());
            fetches.add(() -> Map.entry(jar, reposFor.apply(owner).tryFetchArtifact(jar)));
        }
        for (var hit : ArtifactFanOut.all(
                fetches,
                h -> h.getValue().isPresent() && board.landed(h.getKey().toString()))) {
            Optional<RepoGroup.RepoFetched> fetched = hit.getValue();
            read.put(
                    hit.getKey(),
                    fetched.isPresent() ? parse(fetched.get().fetched().cachePath()) : Declared.NONE);
        }
    }

    /** What {@code jar}'s descriptors declare; {@link Declared#NONE} when it has none or is no zip. */
    private Declared parse(Path jar) {
        List<Coordinate> dependencies = new ArrayList<>();
        List<String> condition = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Rule rule : rules) {
                ZipEntry entry = zip.getEntry(rule.descriptor());
                if (entry == null) continue;
                Properties props = new Properties();
                try (InputStream in = zip.getInputStream(entry)) {
                    props.load(in);
                }
                for (String spec : split(props.getProperty(rule.dependenciesKey()))) {
                    Coordinate dependency = dependency(spec);
                    if (dependency != null && !dependencies.contains(dependency)) dependencies.add(dependency);
                }
                for (String spec : split(props.getProperty(rule.conditionKey()))) {
                    String key = conditionKey(spec);
                    if (key != null && !condition.contains(key)) condition.add(key);
                }
            }
        } catch (IOException | RuntimeException notAZip) {
            return Declared.NONE;
        }
        return dependencies.isEmpty() && condition.isEmpty() ? Declared.NONE : new Declared(dependencies, condition);
    }

    private static List<String> split(@Nullable String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : value.trim().split("[\\s,]+")) if (!part.isEmpty()) out.add(part);
        return out;
    }

    /**
     * {@code group:artifact[:classifier[:type]]:version} as the plain jar it names; null for any
     * other artifact (a classifier or a type other than jar) or a malformed one.
     */
    static @Nullable Coordinate dependency(String spec) {
        String[] parts = spec.split(":", -1);
        if (parts.length < 3 || parts.length > 5) return null;
        String classifier = parts.length >= 4 ? parts[2] : "";
        String type = parts.length == 5 ? parts[3] : "";
        if (!classifier.isEmpty() || !(type.isEmpty() || PackageId.DEFAULT_TYPE.equals(type))) return null;
        try {
            return Coordinate.of(parts[0], parts[1], parts[parts.length - 1]);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** {@code group:artifact[:classifier[:type]]} as {@link #identities} names it; null when malformed. */
    static @Nullable String conditionKey(String spec) {
        String[] parts = spec.split(":", -1);
        if (parts.length < 2 || parts.length > 4 || parts[0].isBlank() || parts[1].isBlank()) return null;
        String classifier = parts.length >= 3 ? parts[2] : "";
        return classifier.isEmpty() ? parts[0] + ":" + parts[1] : parts[0] + ":" + parts[1] + ":" + classifier;
    }
}
