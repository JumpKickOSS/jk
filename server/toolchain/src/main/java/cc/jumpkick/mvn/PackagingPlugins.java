// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * The plugins that shape the artifact. Shade and {@code jar-with-dependencies} are {@code
 * [application] assembly = true}; a relocation, filter or transformer jk's merge rules do not
 * cover is a row. {@code spring-boot-maven-plugin} is the {@code [spring-boot]} table at the Boot
 * version the chain resolves; {@code native-maven-plugin} is {@code [native]}. Jib and the Docker
 * plugins are rows carrying the {@code [image]} lines to paste, and a war has no jk shape at all.
 */
final class PackagingPlugins {

    /** What the packaging plugins add: a fat jar, a native table and a Boot table, each optional. */
    record Packaging(
            boolean fatJar,
            JkBuild.@Nullable NativeConfig nativeConfig,
            @Nullable PluginConfig springBoot) {}

    private static final String SHADE = "maven-shade-plugin";
    private static final String ASSEMBLY = "maven-assembly-plugin";
    private static final String SPRING_BOOT = "spring-boot-maven-plugin";
    private static final String NATIVE = "native-maven-plugin";
    /** Shade transformers whose effect is jk's default fat-jar merge, so they need no row. */
    private static final Set<String> COVERED_TRANSFORMERS =
            Set.of("ManifestResourceTransformer", "ServicesResourceTransformer");

    private PackagingPlugins() {}

    /** {@code mainClass} is the application main the import already found, if any. */
    static Packaging map(Model model, @Nullable String mainClass, ImportReport.Builder report) {
        boolean fatJar = mapShade(model, report) | mapAssembly(model, report);
        if (fatJar && mainClass == null) {
            report.warning("a fat jar was requested but no `<mainClass>` was found; `[application] assembly = true`"
                    + " needs `[application] main`, so no `[application]` table was written — add both.");
            fatJar = false;
        }
        PluginConfig springBoot = PluginFacts.plugin(model, SPRING_BOOT)
                .map(boot -> mapSpringBoot(boot, model, report))
                .orElse(null);
        JkBuild.NativeConfig nativeConfig = PluginFacts.plugin(model, NATIVE)
                .map(plugin -> mapNative(plugin, mainClass))
                .orElse(null);
        PluginFacts.plugin(model, "jib-maven-plugin").ifPresent(jib -> reportImage(jib, report));
        PluginFacts.plugin(model, "docker-maven-plugin").ifPresent(docker -> reportImage(docker, report));
        // Packaging decides: a parent's <build><plugins> declaration of the war plugin is inherited
        // by every jar module and binds nothing there.
        if ("war".equals(model.getPackaging())) {
            report.error("packaging `war` (`maven-war-plugin`) is not supported: jk builds jars, Boot jars and"
                    + " native images. Keep building this module with `jk mvn package`.");
        }
        return new Packaging(fatJar, nativeConfig, springBoot);
    }

    /** Shade is the fat jar; what jk's merge rules do not do is a row per construct. */
    private static boolean mapShade(Model model, ImportReport.Builder report) {
        Optional<Plugin> shade = PluginFacts.plugin(model, SHADE);
        if (shade.isEmpty()) return false;
        List<String> relocations = new ArrayList<>();
        List<String> filters = new ArrayList<>();
        List<String> transformers = new ArrayList<>();
        boolean minimize = false;
        for (Xpp3Dom config : PluginFacts.configurations(shade.get())) {
            PluginFacts.visit(config, "relocation", relocation -> {
                String pattern = PluginFacts.child(relocation, "pattern");
                String shaded = PluginFacts.child(relocation, "shadedPattern");
                if (pattern != null) relocations.add(pattern + (shaded == null ? "" : " → " + shaded));
            });
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
        if (!relocations.isEmpty()) {
            report.warning("`maven-shade-plugin` `<relocations>` " + String.join(", ", relocations)
                    + " — jk's fat jar does not rewrite packages; the classes are bundled under their own names.");
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

    /** {@code jar-with-dependencies} is the fat jar; any other descriptor is a row. */
    private static boolean mapAssembly(Model model, ImportReport.Builder report) {
        Optional<Plugin> assembly = PluginFacts.plugin(model, ASSEMBLY);
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
     * {@code <imageName>} / {@code <name>}) as the {@code [image]} lines to paste: jk builds the
     * image from the module itself, so the table is the whole configuration.
     */
    private static void reportImage(Plugin plugin, ImportReport.Builder report) {
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
        StringBuilder lines = new StringBuilder("[image]");
        if (base != null) lines.append(" base = \"").append(base).append('"');
        if (target != null) lines.append(imageReference(target));
        report.warning("`" + plugin.getArtifactId() + "` — jk builds the image itself (`jk image`); paste `"
                + lines + "` into jk.toml" + (base == null && target == null ? " and fill in the keys" : "")
                + ", then compare against docs/user/images.md for ports, env and labels.");
    }

    /** {@code registry/name:tag} as the {@code [image]} keys, each written only when the reference carries it. */
    private static String imageReference(String reference) {
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
        StringBuilder out = new StringBuilder();
        if (registry != null) out.append(" registry = \"").append(registry).append('"');
        out.append(" name = \"").append(rest).append('"');
        if (tag != null) out.append(" tag = \"").append(tag).append('"');
        return out.toString();
    }
}
