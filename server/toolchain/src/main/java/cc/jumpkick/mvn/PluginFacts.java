// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * What the import reads out of an effective model's {@code <properties>} and {@code
 * <build><plugins>}: the compiler release, the Kotlin plugin, the application main class and
 * custom jar-manifest attributes. Values are already interpolated; one that still carries {@code
 * ${...}} had no definition anywhere in the chain and is skipped.
 */
final class PluginFacts {

    private static final String[] COMPILER_PROPERTIES = {
        "maven.compiler.release", "maven.compiler.target", "maven.compiler.source"
    };
    private static final String[] COMPILER_CONFIG = {"release", "target", "source"};
    private static final String[] MAIN_CLASS_PROPERTIES = {"start-class", "exec.mainClass", "main.class", "mainClass"};

    private PluginFacts() {}

    static List<Plugin> plugins(Model model) {
        Build build = model.getBuild();
        return build == null ? List.of() : build.getPlugins();
    }

    static Optional<Plugin> plugin(Model model, String artifactId) {
        return plugins(model).stream()
                .filter(p -> artifactId.equals(p.getArtifactId()))
                .findFirst();
    }

    /** The Java release: {@code maven.compiler.*} properties, else the compiler plugin's configuration. */
    static Optional<Integer> compilerRelease(Model model) {
        Properties props = model.getProperties();
        for (String key : COMPILER_PROPERTIES) {
            Optional<Integer> level = javaLevel(props.getProperty(key));
            if (level.isPresent()) return level;
        }
        Optional<Plugin> compiler = plugin(model, "maven-compiler-plugin");
        if (compiler.isEmpty()) return Optional.empty();
        for (Xpp3Dom config : configurations(compiler.get())) {
            for (String key : COMPILER_CONFIG) {
                Optional<Integer> level = javaLevel(text(config.getChild(key)));
                if (level.isPresent()) return level;
            }
        }
        return Optional.empty();
    }

    /** {@code 17} → 17; the {@code 1.8} spelling → 8. */
    static Optional<Integer> javaLevel(@Nullable String value) {
        if (value == null || value.isBlank() || value.contains("${")) return Optional.empty();
        String v = value.trim();
        if (v.startsWith("1.")) v = v.substring(2);
        try {
            return Optional.of(Integer.parseInt(v));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** A declared {@code kotlin-maven-plugin}; {@code version} is null when nothing in the chain pins it. */
    record Kotlin(@Nullable String version) {}

    /**
     * The Kotlin plugin when declared, with the plugin's own version, else the {@code
     * kotlin.version} property. Empty when the plugin is absent (a Java project).
     */
    static Optional<Kotlin> kotlin(Model model) {
        Optional<Plugin> plugin = plugin(model, "kotlin-maven-plugin");
        if (plugin.isEmpty()) return Optional.empty();
        String version = usable(plugin.get().getVersion());
        if (version == null) {
            Properties props = model.getProperties();
            version = usable(props.getProperty("kotlin.version"));
            if (version == null) version = usable(props.getProperty("kotlin.compiler.version"));
        }
        return Optional.of(new Kotlin(version));
    }

    /**
     * The application main class: the first {@code <mainClass>} in any plugin configuration
     * (jar / assembly / shade / exec / boot), else a {@code start-class}-style property.
     */
    static @Nullable String mainClass(Model model) {
        List<String> found = new ArrayList<>();
        for (Plugin plugin : plugins(model)) {
            for (Xpp3Dom config : configurations(plugin)) {
                visit(config, "mainClass", node -> {
                    String v = usable(node.getValue());
                    if (v != null) found.add(v);
                });
            }
            if (!found.isEmpty()) return found.getFirst();
        }
        for (String key : MAIN_CLASS_PROPERTIES) {
            String v = usable(model.getProperties().getProperty(key));
            if (v != null) return v;
        }
        return null;
    }

    /**
     * Custom jar-manifest attributes from every {@code <archive><manifestEntries>} (jar / assembly
     * / shade). {@code Main-Class} is excluded — it routes to {@code [application].main}.
     */
    static Map<String, String> manifestEntries(Model model) {
        Map<String, String> attrs = new LinkedHashMap<>();
        for (Plugin plugin : plugins(model)) {
            for (Xpp3Dom config : configurations(plugin)) {
                visit(config, "manifestEntries", entries -> {
                    for (Xpp3Dom entry : entries.getChildren()) {
                        String value = usable(entry.getValue());
                        if (value == null || entry.getName().equalsIgnoreCase("Main-Class")) continue;
                        attrs.put(entry.getName(), value);
                    }
                });
            }
        }
        return attrs;
    }

    /** The plugin's configuration followed by each execution's, in declaration order. */
    static List<Xpp3Dom> configurations(Plugin plugin) {
        List<Xpp3Dom> out = new ArrayList<>();
        if (plugin.getConfiguration() instanceof Xpp3Dom dom) out.add(dom);
        for (PluginExecution execution : plugin.getExecutions()) {
            if (execution.getConfiguration() instanceof Xpp3Dom dom) out.add(dom);
        }
        return out;
    }

    /** Depth-first over {@code node}, calling {@code sink} on every element named {@code name}. */
    private static void visit(Xpp3Dom node, String name, Consumer<Xpp3Dom> sink) {
        if (name.equals(node.getName())) {
            sink.accept(node);
            return;
        }
        for (Xpp3Dom child : node.getChildren()) visit(child, name, sink);
    }

    private static @Nullable String text(@Nullable Xpp3Dom node) {
        return node == null ? null : node.getValue();
    }

    /** Trimmed, or null when blank or still a {@code ${...}} placeholder. */
    static @Nullable String usable(@Nullable String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() || v.contains("${") ? null : v;
    }
}
