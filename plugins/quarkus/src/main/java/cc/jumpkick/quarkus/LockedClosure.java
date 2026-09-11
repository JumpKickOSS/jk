// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * jk's resolved RUNTIME closure — the lockfile's answer to "what ships" — as the engine hands it
 * to the augment: one {@code group:artifact:version<TAB>jar} line per locked artifact.
 *
 * <p>This is the <em>whole</em> input to the Quarkus application model. Keeping only the jars that
 * look like extensions and letting {@code BootstrapAppModelResolver} re-resolve the rest under the
 * platform BOM would let Maven's nearest-wins — not {@code jk-lock.toml} — decide what lands in
 * {@code quarkus-app/lib/main}. The lockfile is law: every runtime artifact that ships is one of
 * these, at this version, out of this jar.
 *
 * <p>Two identities are in play and they are deliberately different. A locked artifact is keyed on
 * {@code group:artifact} because that is all the engine's runtime-entry list can express; the
 * resolved model keys on the full {@code group:artifact:classifier:type}. So one locked coordinate
 * can legitimately answer for several classified variants Aether resolved (native transports), and
 * the reconciliation pins their version without touching their classifier.
 */
final class LockedClosure {

    /**
     * One locked runtime artifact.
     *
     * @param workspace the coordinate was synthesized from the jar's own name because jk had none
     *     to give — a path/workspace sibling, which has no Maven layout to resolve from
     */
    record Artifact(String group, String artifact, String version, Path jar, boolean workspace) {

        /** {@code group:artifact} — the identity the lock and the second resolver share. */
        String ga() {
            return group + ":" + artifact;
        }

        /** {@code group:artifact:version} — for messages. */
        String coords() {
            return group + ":" + artifact + ":" + version;
        }
    }

    /** One runtime dependency the second resolver produced, reduced to what the lock judges. */
    record Resolved(String group, String artifact, String classifier, String version) {

        String ga() {
            return group + ":" + artifact;
        }

        String coords() {
            return group + ":" + artifact + (classifier.isEmpty() ? "" : ":" + classifier) + ":" + version;
        }
    }

    /**
     * What one resolved runtime dependency must become before it ships.
     *
     * @param ship false when the lock does not name this coordinate at all — it is dropped from the
     *     runtime classpath rather than shipped unlocked
     * @param version the locked version, or {@code null} when not shipping
     * @param jar the locked jar, or {@code null} to keep the file the resolver found — which is
     *     what a classified variant does, because the runtime list names one jar per
     *     {@code group:artifact} and it is the unclassified one
     */
    record Pin(
            Resolved dep,
            boolean ship,
            @Nullable String version,
            @Nullable Path jar) {}

    /**
     * The lock's verdict on a resolved runtime classpath, one {@link Pin} per dependency in the
     * order it was asked about, plus everything worth saying out loud about the disagreement.
     *
     * @param overrides coordinates the resolver versioned differently; the lock won
     * @param unlocked coordinates the lock does not name; they do not ship
     * @param unresolved locked coordinates absent from the resolved runtime classpath — an
     *     extension's {@code excluded-artifacts} removed them, which is framework knowledge jk has
     *     no business overriding
     */
    record Plan(List<Pin> pins, List<String> overrides, List<String> unlocked, List<String> unresolved) {}

    /** Coordinate the lock could not name (a path/workspace jar) before synthesis. */
    private static final String UNKNOWN_GROUP = "unknown";

    /** Group synthesized coordinates land under — never a real Maven group. */
    static final String WORKSPACE_GROUP = "jk.workspace";

    private final List<Artifact> artifacts;
    private final Map<String, Artifact> byGa;

    private LockedClosure(List<Artifact> artifacts) {
        Map<String, Artifact> index = new LinkedHashMap<>();
        List<String> ambiguous = new ArrayList<>();
        for (Artifact a : artifacts) {
            if (index.putIfAbsent(a.ga(), a) != null) {
                ambiguous.add(a.ga());
            }
        }
        if (!ambiguous.isEmpty()) {
            // Two lock entries under one group:artifact can only differ by classifier, and the
            // runtime-entry list drops classifiers — so there is no honest way to say which jar
            // answers for which. Refuse rather than ship a coin flip.
            throw new IllegalStateException("jk's runtime closure names " + ambiguous
                    + " more than once; the augment's runtime list carries no classifier, so the"
                    + " shipped jar would be ambiguous");
        }
        this.artifacts = List.copyOf(artifacts);
        this.byGa = Map.copyOf(index);
    }

    static LockedClosure of(List<Artifact> artifacts) {
        return new LockedClosure(artifacts);
    }

    /** Parse the runtime list the engine writes for the augment fork. */
    static LockedClosure parse(Path tsv) throws IOException {
        List<Artifact> out = new ArrayList<>();
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.split("\t", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("bad runtime list line: " + line);
            }
            String[] gav = parts[0].split(":", 3);
            if (gav.length != 3) {
                throw new IllegalArgumentException("bad GAV: " + parts[0]);
            }
            out.add(synthesize(gav[0], gav[1], gav[2], Path.of(parts[1])));
        }
        return of(out);
    }

    /**
     * A locked artifact from one runtime-list row. Path/workspace siblings arrive as
     * {@code unknown:unknown:0} (or a bare version with a non-store jar) and get a coordinate
     * derived from the jar's {@code name-version.jar} filename, so they can be installed into the
     * bootstrap repo and declared like any other dependency.
     */
    static Artifact synthesize(String group, String artifact, String version, Path jar) {
        boolean workspace = group.startsWith(UNKNOWN_GROUP)
                || "0".equals(version)
                        && jar != null
                        && !jar.toString().replace('\\', '/').contains("/repos/");
        if (!workspace) {
            return new Artifact(group, artifact, version, jar, false);
        }
        String file = jar.getFileName().toString();
        String base = file.endsWith(".jar") ? file.substring(0, file.length() - 4) : file;
        // domain-0.1.0.jar → artifact=domain version=0.1.0
        String name = base;
        String ver = "0.1.0";
        int dash = base.lastIndexOf('-');
        if (dash > 0 && dash < base.length() - 1) {
            String maybeVer = base.substring(dash + 1);
            if (maybeVer.matches("[0-9].*")) {
                name = base.substring(0, dash);
                ver = maybeVer;
            }
        }
        return new Artifact(WORKSPACE_GROUP, name, ver, jar, true);
    }

    /** Every locked artifact, in lock order. */
    List<Artifact> artifacts() {
        return artifacts;
    }

    /** The locked artifact answering for a {@code group:artifact}, if the lock names one. */
    Optional<Artifact> byGa(String ga) {
        return Optional.ofNullable(byGa.get(ga));
    }

    /**
     * Maven-layout mirror roots under jk's store, derived from the locked jars' own locations
     * ({@code <store>/repos/<name>/...}). Every sibling repo dir is a valid resolver tail, and
     * deriving them from the jars jk already handed us beats rebuilding product dirs from
     * {@code user.home} — that guesses wrong the moment {@code JK_STORE_DIR} differs.
     */
    List<Path> mirrorRepoRoots() {
        List<Path> out = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        String marker = File.separator + "repos" + File.separator;
        for (Artifact a : artifacts) {
            if (a.jar() == null) continue;
            String sp = a.jar().toString();
            int i = sp.indexOf(marker);
            if (i <= 0) continue;
            Path reposRoot = Path.of(sp.substring(0, i)).resolve("repos");
            if (!Files.isDirectory(reposRoot)) continue;
            try (var kids = Files.list(reposRoot)) {
                for (Path repo : kids.filter(Files::isDirectory).sorted().toList()) {
                    if (seen.add(repo)) out.add(repo);
                }
            } catch (IOException ignored) {
                // unreadable mirror root — the resolver falls back to its other tails
            }
            break;
        }
        return out;
    }

    /**
     * Judge a resolved runtime classpath against the lock and say what ships.
     *
     * <p>Every shipped artifact is the lock's, at the lock's version — a coordinate the resolver
     * picked differently loses, and says so. A runtime artifact the lock does not name does not
     * ship at all: it never passed through jk's solver, so it has no checksum, no SBOM entry, and
     * (the usual cause being an OS-activated Maven profile) it would make the same lock produce a
     * different application on a different host. Every other jk packager ships the lock and only
     * the lock; this makes {@code quarkus-app/lib} agree.
     *
     * <p>The reverse gap is not jk's to close. A locked artifact missing from the resolved runtime
     * classpath was removed by an extension's {@code excluded-artifacts} (or by Quarkus's own
     * {@code quarkus-ide-launcher} rule) — framework knowledge about duplicate and repackaged
     * classes, not a resolution disagreement. It is reported, not reinstated.
     */
    Plan plan(List<Resolved> resolvedRuntime) {
        Map<String, Integer> variants = new LinkedHashMap<>();
        Set<String> covered = new LinkedHashSet<>();
        for (Resolved r : resolvedRuntime) {
            if (byGa.containsKey(r.ga())) variants.merge(r.ga(), 1, Integer::sum);
        }

        List<Pin> pins = new ArrayList<>(resolvedRuntime.size());
        List<String> overrides = new ArrayList<>();
        List<String> unlocked = new ArrayList<>();
        for (Resolved r : resolvedRuntime) {
            Artifact locked = byGa.get(r.ga());
            if (locked == null) {
                unlocked.add(r.coords());
                pins.add(new Pin(r, false, null, null));
                continue;
            }
            if (covered.add(r.ga()) && !locked.version().equals(r.version())) {
                overrides.add(r.ga() + ": maven picked " + r.version() + ", lock pins " + locked.version());
            }
            // Counted above for every GA the lock knows, and this one is locked.
            boolean sole = Objects.requireNonNull(variants.get(r.ga())) == 1
                    && r.classifier().isEmpty();
            pins.add(new Pin(r, true, locked.version(), sole ? locked.jar() : null));
        }

        List<String> unresolved = new ArrayList<>();
        for (Artifact a : artifacts) {
            if (!covered.contains(a.ga())) unresolved.add(a.coords());
        }
        return new Plan(List.copyOf(pins), List.copyOf(overrides), List.copyOf(unlocked), List.copyOf(unresolved));
    }
}
