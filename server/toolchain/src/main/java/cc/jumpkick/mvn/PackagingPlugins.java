// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.ImageTable;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * The plugins that shape the artifact. Shade and {@code jar-with-dependencies} are {@code
 * [application] assembly = true}; a shade {@code <relocation>} of a package is the assembly's
 * {@code relocate} rule, and a filter, a transformer or a relocation shape jk's rules do not cover
 * is a row. {@code spring-boot-maven-plugin} is the {@code [spring-boot]} table at the Boot
 * version the chain resolves; {@code quarkus-maven-plugin} is the {@code [quarkus]} table at the
 * platform version; {@code native-maven-plugin} is {@code [native]}. Jib's and the Docker plugins'
 * base and target images are the {@code [image]} table, and a war has no jk shape at all.
 */
final class PackagingPlugins {

    /**
     * What the packaging plugins add: a fat jar with its package relocations, a native table, a
     * Boot table, a Quarkus table and an image table, each optional ({@code image} is {@link
     * ImageTable#EMPTY} without one).
     */
    record Packaging(
            boolean fatJar,
            Map<String, String> relocate,
            JkBuild.@Nullable NativeConfig nativeConfig,
            @Nullable PluginConfig springBoot,
            @Nullable PluginConfig quarkus,
            ImageTable image) {}

    /**
     * One shade {@code <relocation>}: the package moved and where to; {@code shaded} is null when
     * the POM omits it. {@code rawString} is Shade's literal-string mode and {@code filtered} its
     * {@code <includes>}/{@code <excludes>} narrowing — neither is a whole-package rule, so
     * neither becomes a {@code relocate} entry.
     */
    record Relocation(String pattern, @Nullable String shaded, boolean rawString, boolean filtered) {
        String label() {
            return pattern + (shaded == null ? "" : " → " + shaded);
        }

        /** A whole dotted package moved to another: what {@code [application] relocate} spells. */
        boolean wholePackage() {
            return shaded != null && !rawString && !filtered && !pattern.contains("/") && !shaded.contains("/");
        }
    }

    private static final String SHADE = "maven-shade-plugin";
    private static final String ASSEMBLY = "maven-assembly-plugin";
    private static final String SPRING_BOOT = "spring-boot-maven-plugin";
    private static final String QUARKUS = "quarkus-maven-plugin";
    private static final String NATIVE = "native-maven-plugin";
    /** Shade transformers whose effect is jk's default fat-jar merge, so they need no row. */
    private static final Set<String> COVERED_TRANSFORMERS =
            Set.of("ManifestResourceTransformer", "ServicesResourceTransformer");

    private PackagingPlugins() {}

    /**
     * {@code mainClass} is the application main the import already found, if any. A plugin the POM
     * declares bare whose executions live only in an inactive profile ({@link
     * PluginFacts#boundOnlyInProfile}) runs under Maven only with {@code -P}, so it shapes nothing
     * here: a row names the profile and the table or fat jar it would have written.
     */
    static Packaging map(EffectiveModel em, @Nullable String mainClass, ImportReport.Builder report) {
        Model model = em.model();
        Map<String, String> relocate = new LinkedHashMap<>();
        boolean fatJar = mapShade(em, relocate, report) | mapAssembly(em, report);
        if (fatJar && mainClass == null) {
            report.warning("a fat jar was requested but no `<mainClass>` was found; `[application] assembly = true`"
                    + " needs `[application] main`, so no `[application]` table was written — add both"
                    + (relocate.isEmpty()
                            ? "."
                            : ", and `relocate = " + relocate + "` beside them for the shade relocations."));
            fatJar = false;
            relocate.clear();
        }
        PluginConfig springBoot = PluginFacts.plugin(model, SPRING_BOOT)
                .filter(boot -> repackages(boot, mainClass, report))
                .map(boot -> mapSpringBoot(boot, model, report))
                .orElse(null);
        PluginConfig quarkus = PluginFacts.plugin(model, QUARKUS)
                .map(plugin -> mapQuarkus(plugin, model, report))
                .orElse(null);
        JkBuild.NativeConfig nativeConfig = active(em, NATIVE, "no `[native]` table is written", report)
                .map(plugin -> mapNative(plugin, mainClass))
                .orElse(null);
        ImageTable image = active(em, "jib-maven-plugin", "no `[image]` table is written", report)
                .or(() -> active(em, "docker-maven-plugin", "no `[image]` table is written", report))
                .map(plugin -> mapImage(plugin, report))
                .orElse(ImageTable.EMPTY);
        // Packaging decides: a parent's <build><plugins> declaration of the war plugin is inherited
        // by every jar module and binds nothing there.
        if ("war".equals(model.getPackaging())) {
            report.error("packaging `war` (`maven-war-plugin`) is not supported: jk builds jars, Boot jars and"
                    + " native images. Keep building this module with `jk mvn package`.");
        }
        return new Packaging(
                fatJar,
                Collections.unmodifiableMap(new LinkedHashMap<>(relocate)),
                nativeConfig,
                springBoot,
                quarkus,
                image);
    }

    /** Every {@code <relocation>} of the module's shade plugin, in declaration order; empty without the plugin. */
    static List<Relocation> relocations(Model model) {
        Optional<Plugin> shade = PluginFacts.plugin(model, SHADE);
        if (shade.isEmpty()) return List.of();
        List<Relocation> relocations = new ArrayList<>();
        for (Xpp3Dom config : PluginFacts.configurations(shade.get())) {
            PluginFacts.visit(config, "relocation", relocation -> {
                String pattern = PluginFacts.child(relocation, "pattern");
                if (pattern == null) return;
                boolean raw = Boolean.parseBoolean(PluginFacts.child(relocation, "rawString"));
                boolean filtered =
                        hasChildren(relocation.getChild("includes")) || hasChildren(relocation.getChild("excludes"));
                relocations.add(new Relocation(pattern, PluginFacts.child(relocation, "shadedPattern"), raw, filtered));
            });
        }
        return relocations;
    }

    /**
     * The module's declaration of {@code artifactId} when Maven would run it here; a declaration
     * bound only in an inactive profile is a row saying so and {@code written} is what stays out.
     */
    private static Optional<Plugin> active(
            EffectiveModel em, String artifactId, String written, ImportReport.Builder report) {
        Optional<Plugin> plugin = PluginFacts.plugin(em.model(), artifactId);
        if (plugin.isEmpty()) return plugin;
        Optional<String> profile = PluginFacts.boundOnlyInProfile(em, plugin.get());
        if (profile.isEmpty()) return plugin;
        report.warning("`" + artifactId + "` is declared without executions or configuration; Maven profile `"
                + profile.get() + "` supplies them, and that profile is not active on this machine, so the plugin"
                + " runs only under `-P " + profile.get() + "`; " + written
                + ". Activate the profile and re-import, or add the table to jk.toml yourself.");
        return Optional.empty();
    }

    private static boolean hasChildren(@Nullable Xpp3Dom node) {
        return node != null && node.getChildCount() > 0;
    }

    /**
     * Shade is the fat jar; a whole-package relocation is a {@code relocate} entry written into
     * {@code relocate}, and what jk's rules do not do is a row per construct.
     */
    private static boolean mapShade(EffectiveModel em, Map<String, String> relocate, ImportReport.Builder report) {
        Model model = em.model();
        Optional<Plugin> shade = active(em, SHADE, "no fat jar is written", report);
        if (shade.isEmpty()) return false;
        List<String> partial = new ArrayList<>();
        for (Relocation relocation : relocations(model)) {
            if (relocation.wholePackage()) {
                relocate.putIfAbsent(relocation.pattern(), Objects.requireNonNull(relocation.shaded()));
            } else if (!relocation.rawString() || !relocate.containsValue(slashesToDots(relocation.shaded()))) {
                partial.add(relocation.label());
            }
        }
        List<String> filters = new ArrayList<>();
        List<String> transformers = new ArrayList<>();
        boolean minimize = false;
        for (Xpp3Dom config : PluginFacts.configurations(shade.get())) {
            PluginFacts.visit(config, "filter", filter -> {
                String artifact = PluginFacts.child(filter, "artifact");
                if (artifact != null) filters.add(artifact);
            });
            PluginFacts.visit(config, "transformer", transformer -> {
                String implementation = transformer.getAttribute("implementation");
                if (implementation == null) return;
                String simple = implementation.substring(implementation.lastIndexOf('.') + 1);
                if (!COVERED_TRANSFORMERS.contains(simple)) transformers.add(simple);
            });
            minimize |= Boolean.parseBoolean(PluginFacts.child(config, "minimizeJar"));
        }
        if (!partial.isEmpty()) {
            report.warning("`maven-shade-plugin` `<relocations>` " + String.join(", ", partial)
                    + " — `[application] relocate` moves whole packages; a relocation with `<includes>`,"
                    + " `<excludes>` or `<rawString>`, or one spelled as a path, is not written and those"
                    + " classes are bundled under their own names.");
        }
        if (!filters.isEmpty()) {
            report.warning("`maven-shade-plugin` `<filters>` on " + String.join(", ", filters)
                    + " — jk's fat jar bundles every dependency jar whole; the filtered entries are kept.");
        }
        if (!transformers.isEmpty()) {
            report.warning("`maven-shade-plugin` transformers " + String.join(", ", transformers)
                    + " — jk's fat jar concatenates `META-INF/services/*` and Spring's registries and takes the"
                    + " first copy of anything else; those transformers were not applied.");
        }
        if (minimize) {
            report.warning("`maven-shade-plugin` `<minimizeJar>true</minimizeJar>` — jk's nearest is"
                    + " `[application] minified = true` (R8), which is opt-in and audited; the fat jar was written"
                    + " unminimized.");
        }
        return true;
    }

    /** A raw-string rule's target as the dotted package it mirrors, or empty when it names none. */
    private static String slashesToDots(@Nullable String shaded) {
        return shaded == null ? "" : shaded.replace('/', '.');
    }

    /** {@code jar-with-dependencies} is the fat jar; any other descriptor is a row. */
    private static boolean mapAssembly(EffectiveModel em, ImportReport.Builder report) {
        Optional<Plugin> assembly = active(em, ASSEMBLY, "no fat jar is written", report);
        if (assembly.isEmpty()) return false;
        boolean fatJar = false;
        List<String> other = new ArrayList<>();
        for (Xpp3Dom config : PluginFacts.configurations(assembly.get())) {
            PluginFacts.visit(config, "descriptorRef", ref -> {
                String value = PluginFacts.usable(ref.getValue());
                if ("jar-with-dependencies".equals(value)) return;
                if (value != null) other.add(value);
            });
            PluginFacts.visit(config, "descriptor", descriptor -> {
                String value = PluginFacts.usable(descriptor.getValue());
                if (value != null) other.add(value);
            });
            Xpp3Dom refs = config.getChild("descriptorRefs");
            if (refs != null) {
                for (Xpp3Dom ref : refs.getChildren()) {
                    fatJar |= "jar-with-dependencies".equals(PluginFacts.usable(ref.getValue()));
                }
            }
        }
        if (!other.isEmpty()) {
            report.warning("`maven-assembly-plugin` descriptors " + String.join(", ", other)
                    + " — jk packages a fat jar (`jar-with-dependencies`) and nothing else from an assembly"
                    + " descriptor; those assemblies were not imported.");
        }
        return fatJar;
    }

    /**
     * Whether the Boot plugin packages this module: not skipped, and either bound to its {@code
     * repackage} goal (the starter parent binds it in {@code pluginManagement}, so the effective
     * model carries the execution) or told a {@code <mainClass>}. A bare declaration on a module
     * with neither is a library that lists the plugin and runs nothing under Maven, and a {@code
     * [spring-boot]} table would ask jk's Boot packager for a main the module does not have.
     */
    private static boolean repackages(Plugin boot, @Nullable String mainClass, ImportReport.Builder report) {
        for (Xpp3Dom config : PluginFacts.configurations(boot)) {
            if (EnvValues.parseBool(PluginFacts.child(config, "skip")).orElse(false)) {
                report.warning("`spring-boot-maven-plugin` is skipped (`<skip>true</skip>`), so this module is not"
                        + " the Boot jar; no `[spring-boot]` table is written.");
                return false;
            }
        }
        if (mainClass != null) return true;
        for (PluginExecution execution : boot.getExecutions()) {
            if (execution.getGoals().contains("repackage")) return true;
        }
        report.warning("`spring-boot-maven-plugin` binds no `repackage` execution and names no `<mainClass>`, so it"
                + " packages nothing under Maven; no `[spring-boot]` table is written and the module packages a"
                + " plain jar.");
        return false;
    }

    /**
     * {@code [spring-boot] version} is the Boot version the chain resolves: the plugin's own, else the
     * managed {@code spring-boot} artifact (the starter parent or an imported BOM), else the {@code
     * spring-boot.version} property. Without one the table is a row, since the key is required.
     */
    private static @Nullable PluginConfig mapSpringBoot(Plugin boot, Model model, ImportReport.Builder report) {
        for (Xpp3Dom config : PluginFacts.configurations(boot)) {
            Xpp3Dom excludes = config.getChild("excludes");
            if (excludes != null && excludes.getChildCount() > 0) {
                List<String> names = new ArrayList<>();
                for (Xpp3Dom exclude : excludes.getChildren()) {
                    String group = PluginFacts.child(exclude, "groupId");
                    String artifact = PluginFacts.child(exclude, "artifactId");
                    if (artifact != null) names.add(group == null ? artifact : group + ":" + artifact);
                }
                report.warning("`spring-boot-maven-plugin` `<excludes>` " + String.join(", ", names)
                        + " — jk's Boot jar nests every runtime dependency; there is no `[spring-boot]` exclude key.");
            }
            if (config.getChild("image") != null) {
                report.warning("`spring-boot-maven-plugin` `<image>` (buildpacks) — jk builds the OCI image itself:"
                        + " `jk image`, configured under `[image]`.");
            }
        }
        String version = PluginFacts.usable(boot.getVersion());
        if (version == null) version = PluginFacts.managedVersion(model, "org.springframework.boot", "spring-boot");
        if (version == null) version = PluginFacts.usable(model.getProperties().getProperty("spring-boot.version"));
        if (version == null) {
            report.warning("`spring-boot-maven-plugin` is declared without a resolvable Boot version; add"
                    + " `[spring-boot] version = \"...\"` to jk.toml yourself.");
            return null;
        }
        return new PluginConfig("spring-boot", Map.of("version", version));
    }

    /**
     * {@code [quarkus] version} is the platform version the chain resolves: the plugin's own (the
     * platform plugin shares the BOM's version), else the managed {@code quarkus-bom}, else the
     * {@code quarkus.platform.version} / {@code quarkus.version} property. Without one the table is
     * a row, since the key is required. The table is what activates the Quarkus plugin: the
     * augment, the fast-jar and the test model {@code @QuarkusTest} boots from.
     */
    private static @Nullable PluginConfig mapQuarkus(Plugin plugin, Model model, ImportReport.Builder report) {
        String version = PluginFacts.usable(plugin.getVersion());
        if (version == null) version = PluginFacts.managedVersion(model, "io.quarkus.platform", "quarkus-bom");
        if (version == null) version = PluginFacts.managedVersion(model, "io.quarkus", "quarkus-bom");
        if (version == null) {
            version = PluginFacts.usable(model.getProperties().getProperty("quarkus.platform.version"));
        }
        if (version == null) version = PluginFacts.usable(model.getProperties().getProperty("quarkus.version"));
        if (version == null) {
            report.warning("`quarkus-maven-plugin` is declared without a resolvable platform version; add"
                    + " `[quarkus] version = \"...\"` to jk.toml yourself.");
            return null;
        }
        return new PluginConfig("quarkus", Map.of("version", version));
    }

    /** {@code <mainClass>}, {@code <imageName>} and {@code <buildArgs>} → {@code [native]} main, name and args. */
    private static JkBuild.NativeConfig mapNative(Plugin plugin, @Nullable String applicationMain) {
        String main = null;
        String name = null;
        List<String> args = new ArrayList<>();
        for (Xpp3Dom config : PluginFacts.configurations(plugin)) {
            if (main == null) main = PluginFacts.child(config, "mainClass");
            if (name == null) name = PluginFacts.child(config, "imageName");
            Xpp3Dom buildArgs = config.getChild("buildArgs");
            if (buildArgs == null) continue;
            if (buildArgs.getChildCount() > 0) {
                for (Xpp3Dom arg : buildArgs.getChildren()) {
                    String value = PluginFacts.usable(arg.getValue());
                    if (value != null) args.add(value);
                }
            } else {
                String text = PluginFacts.usable(buildArgs.getValue());
                if (text != null) args.addAll(List.of(text.split("[,\\s]+")));
            }
        }
        // [application] main already names the entry point; [native] main is for a different one.
        if (main != null && main.equals(applicationMain)) main = null;
        return new JkBuild.NativeConfig(main, name, args, null, JkBuild.NativeMode.SUPPORTED, null);
    }

    /**
     * Jib's {@code <from><image>} / {@code <to><image>} (and the Docker plugins' {@code <from>} /
     * {@code <baseImage>} and {@code <imageName>} / {@code <name>}) as the {@code [image]} table's
     * {@code base}, {@code registry}, {@code name} and {@code tag}: jk builds the image from the
     * module itself, so the table is the whole configuration and a row points at the keys the
     * plugin's other settings land in.
     */
    private static ImageTable mapImage(Plugin plugin, ImportReport.Builder report) {
        String base = null;
        String target = null;
        for (Xpp3Dom config : PluginFacts.configurations(plugin)) {
            Xpp3Dom from = config.getChild("from");
            if (base == null && from != null)
                base = from.getChildCount() == 0
                        ? PluginFacts.usable(from.getValue())
                        : PluginFacts.child(from, "image");
            if (base == null) base = PluginFacts.child(config, "baseImage");
            Xpp3Dom to = config.getChild("to");
            if (target == null && to != null) target = PluginFacts.child(to, "image");
            if (target == null) target = PluginFacts.child(config, "imageName");
            if (target == null) {
                List<String> names = new ArrayList<>();
                PluginFacts.visit(config, "name", node -> {
                    String value = PluginFacts.usable(node.getValue());
                    if (value != null) names.add(value);
                });
                if (!names.isEmpty()) target = names.getFirst();
            }
        }
        ImageReference reference = target == null ? new ImageReference(null, null, null) : ImageReference.parse(target);
        report.warning("`" + plugin.getArtifactId() + "` `<from>` and `<to>` are written as `[image]` base, registry,"
                + " name and tag; jk builds the image itself (`jk image`)"
                + (base == null && target == null ? " — the plugin names neither image, so fill the keys in" : "")
                + ". Ports, env, labels and the entry point live under the same table: see docs/user/images.md.");
        return new ImageTable(
                base,
                reference.name(),
                null,
                List.of(),
                Map.of(),
                Map.of(),
                reference.registry(),
                reference.tag(),
                List.of(),
                null,
                null,
                null,
                null);
    }

    /** {@code registry/name:tag} split into the {@code [image]} keys, each set only when the reference carries it. */
    record ImageReference(
            @Nullable String registry,
            @Nullable String name,
            @Nullable String tag) {
        static ImageReference parse(String reference) {
            String rest = reference;
            String tag = null;
            int colon = rest.lastIndexOf(':');
            if (colon > rest.lastIndexOf('/')) {
                tag = rest.substring(colon + 1);
                rest = rest.substring(0, colon);
            }
            String registry = null;
            int slash = rest.indexOf('/');
            if (slash > 0) {
                String head = rest.substring(0, slash);
                if (head.contains(".") || head.contains(":") || head.equals("localhost")) {
                    registry = head;
                    rest = rest.substring(slash + 1);
                }
            }
            return new ImageReference(registry, rest, tag);
        }
    }
}
