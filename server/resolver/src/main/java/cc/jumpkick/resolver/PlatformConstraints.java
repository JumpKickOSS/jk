// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PinPolicy;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The versions a project's {@code [managed-dependencies]} entries and platform BOMs manage, with
 * the entry or BOM each one came from, and the two ways they touch declared roots: an exact user
 * pin on a managed module wins over the table, and a version-less {@code platform-managed} root
 * takes the managed version. {@link #versions()} is the constraint map the solvers and the row
 * assembler consult; it stays mutable because the language-runtime inject adds to it after
 * collection and the declared test-framework pins ({@link TestEngines#declaredTriggerPins}) after
 * {@link #apply}.
 *
 * <p>The managed table folds first, so a BOM that manages one of its modules at another version
 * gives way under both pin policies, as a POM's own {@code dependencyManagement} entry beats the
 * BOMs it imports under Maven; the lock reports the BOM's say as an override. Two BOMs that manage
 * one module at different versions meet the pin policy: under {@link PinPolicy#NEAREST} the
 * first-declared BOM's version stands, as the first {@code import} does in Maven's {@code
 * dependencyManagement}, and the later BOM's say is kept as an override the lock reports; under
 * {@link PinPolicy#EXACT} the disagreement is a refusal.
 */
public final class PlatformConstraints {

    private final Map<String, String> versions = new LinkedHashMap<>();
    private final Map<String, String> provenance = new LinkedHashMap<>();

    /** Per module, the later BOMs whose say the first-declared BOM's version kept. */
    private final Map<String, ManagementOverride> overrides = new LinkedHashMap<>();

    /** The modules a {@code [managed-dependencies]} entry pins; their provenance is {@code jk.toml:<handle>}. */
    private final Set<String> managedByManifest = new HashSet<>();

    private final PinPolicy pinPolicy;

    /**
     * A module two imported BOMs manage at different versions, resolved as Maven resolves
     * dependencyManagement imports: the first-declared BOM's version stands.
     *
     * @param module the managed {@code group:artifact}
     * @param kept the version the lock uses
     * @param keptBy the first-declared BOM, as {@code group:artifact:version}
     * @param overridden every later BOM with the version it asked for, in declaration order
     */
    record ManagementOverride(
            String module, String kept, String keptBy, boolean keptByManifest, List<String> overridden) {
        String render() {
            if (keptByManifest) {
                return module + " " + kept + " is the project's [managed-dependencies] entry (" + keptBy + "); "
                        + String.join(", ", overridden)
                        + " — the project's own entry wins, as a POM's own dependencyManagement entry beats"
                        + " the BOMs it imports under Maven";
            }
            return module + " " + kept + " is " + keptBy
                    + "'s, the first [platform-dependencies] entry that manages it; "
                    + String.join(", ", overridden)
                    + " — the first-declared BOM wins, as the first import does under Maven";
        }
    }

    private PlatformConstraints(PinPolicy pinPolicy) {
        this.pinPolicy = pinPolicy;
    }

    /**
     * Fold every {@code [managed-dependencies]} entry, then load every {@code [platform-dependencies]}
     * BOM in declaration order and fold its managed versions in; a module an earlier entry manages
     * at another version follows {@code pinPolicy}.
     */
    static PlatformConstraints collect(
            JkBuild project, RepoGroup repos, EffectivePomBuilder pomBuilder, PinPolicy pinPolicy)
            throws IOException, InterruptedException {
        PlatformConstraints c = new PlatformConstraints(pinPolicy == null ? PinPolicy.EXACT : pinPolicy);
        c.fold(project, repos, pomBuilder);
        return c;
    }

    /** {@code group:artifact -> version} for every managed module. */
    Map<String, String> versions() {
        return versions;
    }

    /**
     * What pinned {@code ga} at exactly {@code version}: a BOM as {@code group:artifact:version}, a
     * {@code [managed-dependencies]} entry as {@code jk.toml:<handle>}, or {@code null} when nothing did.
     */
    @Nullable
    String pinnedBy(String ga, String version) {
        String constrained = versions.get(ga);
        return constrained != null && constrained.equals(version) ? provenance.get(ga) : null;
    }

    /**
     * Apply the constraints to the declared roots: drop the table's say on modules the user pinned
     * exactly (except the injected runtimes, which carry the BOM's own version), then give every
     * {@code platform-managed} root its managed version.
     */
    LockRoots.Roots apply(LockRoots.Roots roots, Set<String> injectedRuntimes) {
        stripBomForExactRoots(roots.main(), versions, provenance, injectedRuntimes);
        stripBomForExactRoots(roots.test(), versions, provenance, injectedRuntimes);
        stripBomForExactRoots(roots.processor(), versions, provenance, injectedRuntimes);
        overrides.keySet().retainAll(versions.keySet());
        return new LockRoots.Roots(
                materializePlatformManaged(roots.main(), versions),
                materializePlatformManaged(roots.test(), versions),
                materializePlatformManaged(roots.processor(), versions),
                roots.fileDeps());
    }

    private void fold(JkBuild project, RepoGroup repos, EffectivePomBuilder pomBuilder)
            throws IOException, InterruptedException {
        foldManaged(project, repos);
        for (Dependency platformDep : project.dependencies().of(Scope.PLATFORM)) {
            // Resolve caret/tilde/latest/snapshot against repo metadata, then load *that* BOM's
            // catalog. Exact pins skip metadata. Open ranges are still rejected.
            String bomVersion =
                    PlatformBomVersions.resolve(repos, platformDep.group(), platformDep.name(), platformDep.version());
            Coordinate bomCoord = Coordinate.of(platformDep.group(), platformDep.name(), bomVersion);
            EffectivePom bomPom = pomBuilder.build(bomCoord);
            String bomLabel = bomCoord.toGav();
            for (Map.Entry<String, String> m : managedVersionsByModule(bomPom).entrySet()) {
                String existing = versions.get(m.getKey());
                if (existing == null) {
                    versions.put(m.getKey(), m.getValue());
                    provenance.put(m.getKey(), bomLabel);
                } else if (!existing.equals(m.getValue())) {
                    laterBomDisagrees(m.getKey(), existing, bomLabel, m.getValue());
                }
            }
            // Quarkus (and other) BOMs pin maven-resolver-api/impl via dependencyManagement but
            // often omit named-locks. Bare edges are exact under a platform (EffectivePom fill),
            // but keep the family in the platform map for preferredVersion / pinned-by when an
            // edge arrives without a fill.
            alignMavenResolverFamily(versions, provenance, bomPom, bomLabel);
        }
    }

    /**
     * Every {@code [managed-dependencies]} entry becomes the module's version, provenance {@code
     * jk.toml:<handle>}. A floating selector is resolved against the repositories' metadata as a
     * BOM's is; the first entry on a module wins.
     */
    private void foldManaged(JkBuild project, RepoGroup repos) throws IOException, InterruptedException {
        for (Dependency managed : project.dependencies().of(Scope.MANAGED)) {
            String version = PlatformBomVersions.resolve(repos, managed.group(), managed.name(), managed.version());
            if (versions.putIfAbsent(managed.module(), version) == null) {
                provenance.put(managed.module(), "jk.toml:" + managed.library());
                managedByManifest.add(managed.module());
            }
        }
    }

    /**
     * A later BOM manages {@code module} at {@code asked} where an earlier entry said {@code kept}: the
     * project's own managed entry stands under every policy, a first-declared BOM's under {@link
     * PinPolicy#NEAREST} only.
     */
    private void laterBomDisagrees(String module, String kept, String bomLabel, String asked) {
        String keptBy = Objects.requireNonNull(provenance.get(module));
        boolean byManifest = managedByManifest.contains(module);
        if (!byManifest && pinPolicy != PinPolicy.NEAREST) {
            throw new IllegalStateException("platform BOM conflict on `" + module + "`: " + keptBy
                    + " constrains to " + kept + ", but " + bomLabel + " constrains to " + asked
                    + ". Pick one BOM, pin the coord explicitly, or set [resolve] pins = \"nearest\""
                    + " to take the first-declared BOM's version as Maven does.");
        }
        overrides
                .computeIfAbsent(
                        module, k -> new ManagementOverride(module, kept, keptBy, byManifest, new ArrayList<>()))
                .overridden()
                .add(bomLabel + " constrains to " + asked);
    }

    /**
     * One line per module a later BOM manages at another version, in the order the modules were
     * met: a module the project's managed table pins under either policy, and one two BOMs disagree
     * on under {@link PinPolicy#NEAREST} only, where under {@link PinPolicy#EXACT} it is a refusal.
     * A module the project pins exactly is not here: the pin beats every BOM.
     */
    List<String> renderedOverrides() {
        return overrides.values().stream().map(ManagementOverride::render).toList();
    }

    /**
     * One version per {@code group:artifact} from a BOM's managed entries. Maven manages per
     * classifier, and a BOM's own plain entry can sit beside classified variants an import
     * carries at another version; the platform map is per module, so the plain entry is the
     * module's version and a classified entry only speaks for a module that has no plain one.
     */
    private static Map<String, String> managedVersionsByModule(EffectivePom bomPom) {
        Map<String, String> plain = new LinkedHashMap<>();
        Map<String, String> classifiedOnly = new LinkedHashMap<>();
        for (Pom.Dep m : bomPom.managedDependencies()) {
            if (m.version() == null || m.version().isBlank()) continue;
            boolean classified = m.classifier() != null && !m.classifier().isBlank();
            (classified ? classifiedOnly : plain).putIfAbsent(m.module(), m.version());
        }
        for (Map.Entry<String, String> e : classifiedOnly.entrySet()) plain.putIfAbsent(e.getKey(), e.getValue());
        return plain;
    }

    /**
     * Artifacts that must share one {@code maven-resolver} line. When a platform BOM manages any
     * core resolver jar (or declares {@code maven-resolver.version}), pin the rest of the family
     * to that line if still unconstrained.
     */
    private static final List<String> MAVEN_RESOLVER_FAMILY = List.of(
            "maven-resolver-api",
            "maven-resolver-spi",
            "maven-resolver-util",
            "maven-resolver-impl",
            "maven-resolver-named-locks",
            "maven-resolver-connector-basic",
            "maven-resolver-transport-wagon",
            "maven-resolver-transport-http",
            "maven-resolver-transport-file");

    /**
     * Fill maven-resolver family gaps in {@code bomConstraints} so named-locks cannot float to a major line that
     * breaks {@code NamedLockFactory.getLock(String)}. Public: the plugin tool-closure path aligns the same facts.
     */
    public static void alignMavenResolverFamily(
            Map<String, String> bomConstraints,
            Map<String, String> constraintProvenance,
            EffectivePom bomPom,
            String bomLabel) {
        String line = bomPom.properties().get("maven-resolver.version");
        if (line == null || line.isBlank()) {
            // Prefer the BOM's own managed api/impl pin over a line already present from an
            // earlier BOM (those are already in bomConstraints; we only fill gaps).
            for (Pom.Dep m : bomPom.managedDependencies()) {
                if (m.version() == null || m.version().isBlank() || m.module() == null) continue;
                if ("org.apache.maven.resolver:maven-resolver-api".equals(m.module())
                        || "org.apache.maven.resolver:maven-resolver-impl".equals(m.module())) {
                    line = m.version();
                    if (m.module().endsWith(":maven-resolver-api")) break;
                }
            }
        }
        if (line == null || line.isBlank()) return;
        String provenance = bomLabel + " (maven-resolver family)";
        for (String art : MAVEN_RESOLVER_FAMILY) {
            String mod = "org.apache.maven.resolver:" + art;
            if (bomConstraints.putIfAbsent(mod, line) == null) {
                constraintProvenance.put(mod, provenance);
            }
        }
    }

    private static void stripBomForExactRoots(
            List<Dependency> declared,
            Map<String, String> bomConstraints,
            Map<String, String> constraintProvenance,
            Set<String> injectedRuntimes) {
        for (Dependency d : declared) {
            if (d.isPlatformManaged()) continue;
            if (!(d.version() instanceof VersionSelector.Exact)) continue;
            // An INJECTED runtime root is jk's own bookkeeping, not a user override — it
            // already carries the BOM's managed version, and stripping the BOM here would
            // flip every other edge of the GA to raw POM fills (grails-core declares a
            // groovy NEWER than grails-bom manages → unsat,.
            if (injectedRuntimes.contains(d.module())) continue;
            if (bomConstraints.containsKey(d.module())) {
                bomConstraints.remove(d.module());
                constraintProvenance.remove(d.module());
            }
        }
    }

    private static List<Dependency> materializePlatformManaged(
            List<Dependency> declared, Map<String, String> bomConstraints) {
        List<Dependency> roots = new ArrayList<>(declared.size());
        for (Dependency d : declared) {
            if (d.isPlatformManaged()) {
                String managed = bomConstraints.get(d.module());
                if (managed == null) {
                    throw new IllegalStateException("`" + d.module()
                            + "` is declared without a version, but no [platform-dependencies] BOM manages it"
                            + " — add a `version`, or import the BOM that pins it.");
                }
                roots.add(Dependency.of(d.library(), d.module(), VersionSelector.parse("=" + managed))
                        .withOptional(d.optional())
                        .withKind(d.kind())
                        .withClassifier(d.classifier())
                        .withFeatures(d.requestedFeatures(), d.defaultFeatures()));
            } else {
                roots.add(d);
            }
        }
        return roots;
    }
}
