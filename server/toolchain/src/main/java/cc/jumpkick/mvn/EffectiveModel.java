// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.repo.HostClassifiers;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.PomParseException;
import cc.jumpkick.repo.PomParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.StringModelSource;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.resolution.ModelResolver;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.Nullable;

/**
 * One {@code pom.xml} as Maven sees it: the effective model (parents flattened, BOM imports
 * inlined, {@code dependencyManagement} applied, properties interpolated, active-by-default / JDK
 * / OS profiles injected) plus the raw lineage behind it, so the import report can say which
 * ancestor supplied what. Maven's own model builder does the work; parents and BOMs arrive through
 * jk's repository client.
 *
 * <p>When inheritance cannot be applied (a parent no repository has), {@link #failure()} says why
 * and {@link #model()} is the POM's own raw model, so the import degrades instead of aborting.
 */
final class EffectiveModel {

    /** A parent in the chain, nearest first. {@code inReactor}: read from a sibling pom.xml. */
    record Ancestor(String groupId, String artifactId, String version, Model raw, boolean inReactor) {

        String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }

        /** How the report names this parent. */
        String label() {
            return (inReactor ? "workspace parent " : "parent ") + gav();
        }

        List<Dependency> managed() {
            return EffectiveModel.managed(raw);
        }
    }

    private final Model model;
    private final List<Dependency> assembledManagement;
    private final Model raw;
    private final List<Ancestor> ancestors;
    private final List<Profile> activeProfiles;
    private final @Nullable String failure;

    private EffectiveModel(
            Model model,
            List<Dependency> assembledManagement,
            Model raw,
            List<Ancestor> ancestors,
            List<Profile> activeProfiles,
            @Nullable String failure) {
        this.model = model;
        this.assembledManagement = List.copyOf(assembledManagement);
        this.raw = raw;
        this.ancestors = List.copyOf(ancestors);
        this.activeProfiles = List.copyOf(activeProfiles);
        this.failure = failure;
    }

    /**
     * Build the effective model of {@code xml}. {@code pomFile} (when the bytes came from disk) lets
     * Maven follow {@code <parent><relativePath>} and profile {@code <file>} activations; {@code
     * reactor} answers parent lookups from sibling POMs before any repository is asked. The bytes
     * pass through jk's hardened XML parser first, so a DOCTYPE is refused before Maven reads them.
     */
    @SuppressWarnings("deprecation") // StringModelSource is the source type ModelBuildingRequest still takes
    static EffectiveModel build(
            byte[] xml, @Nullable Path pomFile, ModelResolver resolver, @Nullable ReactorModelResolver reactor) {
        PomParser.parseXml(xml);
        DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
        request.setProcessPlugins(false);
        // Two phases on purpose: the phase-one model is inherited and interpolated but its BOM
        // imports are still listed, which is how they reach `[platform]` with resolved versions.
        request.setTwoPhaseBuilding(true);
        request.setSystemProperties(systemProperties());
        request.setModelResolver(resolver);
        request.setWorkspaceModelResolver(reactor);
        if (pomFile != null) {
            request.setPomFile(pomFile.toFile());
        } else {
            request.setModelSource(new StringModelSource(new String(xml, StandardCharsets.UTF_8)));
        }
        ModelBuilder builder = new DefaultModelBuilderFactory().newInstance();
        try {
            ModelBuildingResult phaseOne = builder.build(request);
            List<Dependency> assembledManagement = managed(phaseOne.getEffectiveModel()).stream()
                    .map(Dependency::clone)
                    .toList();
            ModelBuildingResult result = builder.build(request, phaseOne);
            Model effective = Objects.requireNonNull(result.getEffectiveModel(), "effective model");
            String childId = result.getModelIds().getFirst();
            return new EffectiveModel(
                    effective,
                    assembledManagement,
                    result.getRawModel(),
                    ancestors(result, reactor),
                    result.getActivePomProfiles(childId),
                    null);
        } catch (ModelBuildingException e) {
            Model own = rawModel(xml);
            return new EffectiveModel(own, managed(own), own, List.of(), List.of(), describe(e.getProblems()));
        }
    }

    /**
     * What Maven's {@code -D} space holds here: the JVM's system properties, plus the platform words
     * an OS-activated profile or os-maven-plugin would set for this host ({@code javafx.platform},
     * {@code os.detected.classifier}, …), so a classifier spelled with one reads the running host.
     */
    private static Properties systemProperties() {
        Properties props = new Properties();
        props.putAll(System.getProperties());
        HostClassifiers.properties().forEach(props::putIfAbsent);
        return props;
    }

    /** The POM's own model, nothing inherited or interpolated. */
    static Model rawModel(byte[] xml) {
        PomParser.parseXml(xml);
        try {
            return new MavenXpp3Reader().read(new ByteArrayInputStream(xml), false);
        } catch (IOException | XmlPullParserException e) {
            throw new PomParseException("failed to parse POM: " + e.getMessage(), e);
        }
    }

    /** Lineage above the POM, nearest first; the super-POM (no artifactId) is not an ancestor. */
    private static List<Ancestor> ancestors(ModelBuildingResult result, @Nullable ReactorModelResolver reactor) {
        List<Ancestor> out = new ArrayList<>();
        List<String> ids = result.getModelIds();
        for (int i = 1; i < ids.size(); i++) {
            Model raw = result.getRawModel(ids.get(i));
            if (raw == null || raw.getArtifactId() == null) continue;
            String[] gav = ids.get(i).split(":", 3);
            if (gav.length < 3) continue;
            boolean inReactor = reactor != null && reactor.contains(ids.get(i));
            out.add(new Ancestor(gav[0], gav[1], gav[2], raw, inReactor));
        }
        return out;
    }

    private static String describe(List<ModelProblem> problems) {
        String text = problems.stream()
                .filter(p -> p.getSeverity() != ModelProblem.Severity.WARNING)
                .map(ModelProblem::getMessage)
                .distinct()
                .collect(Collectors.joining("; "));
        return text.isEmpty() ? "Maven could not build the effective model" : text;
    }

    // --- accessors ----------------------------------------------------------

    /** The effective model, or the raw one when {@link #failure()} is set. */
    Model model() {
        return model;
    }

    /**
     * The inherited and interpolated {@code dependencyManagement} with {@code import}-scope BOMs still
     * listed: the phase-one table, the one piece of that model the import reads.
     */
    List<Dependency> assembledManagement() {
        return assembledManagement;
    }

    /** The POM's own declarations, uninterpolated. */
    Model raw() {
        return raw;
    }

    List<Ancestor> ancestors() {
        return ancestors;
    }

    List<Profile> activeProfiles() {
        return activeProfiles;
    }

    @Nullable
    String failure() {
        return failure;
    }

    boolean isActive(Profile profile) {
        return activeProfiles.stream().anyMatch(p -> Objects.equals(p.getId(), profile.getId()));
    }

    /**
     * The effective model's copy of one of this POM's profiles — the same declarations with
     * {@code ${...}} placeholders interpolated — or the raw profile when the model could not be
     * built. Profiles are never inherited, so the id is unique to this POM.
     */
    Profile interpolated(Profile raw) {
        return model.getProfiles().stream()
                .filter(p -> Objects.equals(p.getId(), raw.getId()))
                .findFirst()
                .orElse(raw);
    }

    /** The nearest ancestor that is not a sibling pom.xml: the published parent a platform entry can name. */
    Optional<Ancestor> nearestExternal() {
        return ancestors.stream().filter(a -> !a.inReactor()).findFirst();
    }

    /** True when some published ancestor declares dependencyManagement of its own. */
    boolean externalChainManages() {
        return ancestors.stream().anyMatch(a -> !a.inReactor() && !a.managed().isEmpty());
    }

    /** The nearest ancestor whose own dependencyManagement pins {@code key} (a management key). */
    Optional<Ancestor> managedBy(String key) {
        return ancestors.stream()
                .filter(a -> a.managed().stream().anyMatch(d -> !isImport(d) && key.equals(d.getManagementKey())))
                .findFirst();
    }

    /** The nearest ancestor whose own dependencyManagement imports a BOM. */
    Optional<Ancestor> importsBom() {
        return ancestors.stream()
                .filter(a -> a.managed().stream().anyMatch(EffectiveModel::isImport))
                .findFirst();
    }

    /** The nearest ancestor that declares the dependency {@code key} itself. */
    Optional<Ancestor> declaredBy(String key) {
        return ancestors.stream()
                .filter(a -> a.raw().getDependencies().stream().anyMatch(d -> key.equals(d.getManagementKey())))
                .findFirst();
    }

    static boolean isImport(Dependency d) {
        return "import".equalsIgnoreCase(d.getScope()) && "pom".equalsIgnoreCase(d.getType());
    }

    // --- dependencies and management ------------------------------------------

    /**
     * An effective dependency and where it came from. {@code own}: this POM declares it; {@code
     * versionManaged}: it declared no version and {@code source} supplied one. When not own,
     * {@code source} names the ancestor that declares it.
     */
    record Declared(Pom.Dep dep, String key, boolean own, boolean versionManaged, String source) {}

    /** Every effective dependency with a usable coordinate; a broken one is reported and skipped. */
    List<Declared> dependencies(ImportReport.Builder report) {
        Set<String> rawKeys = keys(raw.getDependencies());
        Set<String> rawVersionless = new HashSet<>();
        for (Dependency d : raw.getDependencies()) {
            if (d.getVersion() == null || d.getVersion().isBlank()) rawVersionless.add(d.getManagementKey());
        }
        List<Declared> out = new ArrayList<>();
        for (Dependency d : model.getDependencies()) {
            if (d.getGroupId() == null || d.getArtifactId() == null) {
                report.error(
                        "`<dependency>` without groupId or artifactId (" + d.getManagementKey() + ") was skipped.");
                continue;
            }
            String key = d.getManagementKey();
            boolean own = rawKeys.contains(key);
            boolean versionManaged = own && rawVersionless.contains(key) && PluginFacts.usable(d.getVersion()) != null;
            String source = own
                    ? (versionManaged ? managedSource(key) : "this POM")
                    : declaredBy(key).map(Ancestor::label).orElse("a parent");
            out.add(new Declared(toDep(d), key, own, versionManaged, source));
        }
        return out;
    }

    /** Who supplied the version of a dependency this POM declares without one. */
    private String managedSource(String key) {
        if (ownManaged().stream().anyMatch(m -> !isImport(m) && key.equals(m.getManagementKey()))) {
            return "this POM's dependencyManagement";
        }
        Optional<Ancestor> pinned = managedBy(key);
        if (pinned.isPresent()) return pinned.get().label();
        if (ownManaged().stream().anyMatch(EffectiveModel::isImport)) return "a BOM imported by this POM";
        return importsBom().map(a -> "a BOM imported by " + a.label()).orElse("a parent");
    }

    /**
     * An inline {@code dependencyManagement} entry {@code [managed-dependencies]} carries: a pin no
     * declared dependency uses, or one with {@code <exclusions>}, which govern every edge onto the
     * module and not only a declared one's. {@code owner} labels the POM that wrote it ({@code this
     * POM}, or an ancestor's label); {@code reactorParent} is true when that ancestor is a sibling
     * pom.xml of the reactor, whose table a workspace import writes once on the root.
     */
    record InlinePin(Pom.Dep dep, String owner, boolean reactorParent) {}

    /**
     * How the flattened {@code dependencyManagement} reaches {@code jk.toml}: {@code platform} are
     * the BOM imports to write with their versions resolved; {@code parentPlatform} is the nearest
     * published parent when its chain manages versions of its own, so one {@code [platform]} entry
     * carries the whole inherited table; {@code inline} are the bare pins no declared dependency
     * uses and no platform entry carries, plus every entry with exclusions, which {@code
     * [managed-dependencies]} carries so they govern transitive versions and prune transitive edges
     * as they do under Maven.
     */
    record Management(List<Pom.Dep> platform, @Nullable Ancestor parentPlatform, List<InlinePin> inline) {}

    Management management(Set<String> usedKeys) {
        boolean parentCarries = externalChainManages();
        Set<String> ownKeys = keys(ownManaged());
        List<Pom.Dep> platform = new ArrayList<>();
        List<InlinePin> inline = new ArrayList<>();
        for (Dependency m : assembledManagement) {
            String key = m.getManagementKey();
            boolean own = ownKeys.contains(key);
            Optional<Ancestor> owner = own ? Optional.empty() : (isImport(m) ? importsBom() : managedBy(key));
            boolean carriedByParent =
                    parentCarries && owner.isPresent() && !owner.get().inReactor();
            if (carriedByParent) continue;
            if (isImport(m)) {
                platform.add(toDep(m));
            } else if (!usedKeys.contains(key) || !m.getExclusions().isEmpty()) {
                String label = own ? "this POM" : owner.map(Ancestor::label).orElse("a parent");
                boolean reactorParent = !own && owner.map(Ancestor::inReactor).orElse(false);
                inline.add(new InlinePin(toDep(m), label, reactorParent));
            }
        }
        return new Management(platform, parentCarries ? nearestExternal().orElse(null) : null, inline);
    }

    private List<Dependency> ownManaged() {
        return managed(raw);
    }

    /** A model's own {@code dependencyManagement} entries, empty without the section. */
    private static List<Dependency> managed(@Nullable Model model) {
        DependencyManagement dm = model == null ? null : model.getDependencyManagement();
        return dm == null ? List.of() : dm.getDependencies();
    }

    private static Set<String> keys(List<Dependency> deps) {
        Set<String> keys = new HashSet<>();
        for (Dependency d : deps) keys.add(d.getManagementKey());
        return keys;
    }

    /** Maven's dependency as jk's POM record; the importer maps scopes and kinds from there. */
    static Pom.Dep toDep(Dependency d) {
        List<Pom.Dep.Exclusion> exclusions = new ArrayList<>();
        for (Exclusion e : d.getExclusions()) {
            exclusions.add(new Pom.Dep.Exclusion(e.getGroupId(), e.getArtifactId()));
        }
        return new Pom.Dep(
                d.getGroupId(),
                d.getArtifactId(),
                d.getVersion(),
                d.getScope(),
                d.isOptional(),
                d.getClassifier(),
                d.getType(),
                exclusions);
    }
}
