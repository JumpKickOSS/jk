// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.PluginConfig;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * {@code protobuf-maven-plugin} is the {@code [protobuf]} preset on a module that owns
 * {@code .proto} sources: {@code <protocArtifact>}'s version is {@code version} (the protobuf-java
 * dependency's when the POM names no protoc), {@code <protoSourceRoot>} is {@code src}, the
 * {@code <excludes>} of the plugin and of its {@code compile} executions are {@code exclude} (a
 * {@code test-compile} execution's govern the test protos, which the table does not cover), and the
 * protoc plugin the {@code compile-custom} goal runs (gRPC's) is the {@code [protobuf.<pluginId>]}
 * entry — {@code <pluginArtifact>}'s {@code group:artifact:version} as {@code plugin},
 * {@code <pluginParameter>}'s comma-separated items as {@code options}. The plugin's output under
 * {@code target/generated-sources/protobuf} is the preset's contribution, so a build-helper root
 * inside it is not written as an {@code extra-src}; a module the plugin reaches by inheritance
 * without protos of its own gets neither the table nor the root. A {@code replacer} execution
 * rewriting the generated sources is the table's {@code replace} ({@link ReplacerPlugin}).
 */
final class ProtobufPlugin {

    static final String ARTIFACT = "protobuf-maven-plugin";

    /** What an {@code add-source} root inside the output is, for the build-helper row. */
    static final String ADD_SOURCE_ROW = "the protobuf plugin's output; `[protobuf]` folds the generated Java"
            + " into the compile itself, so no `extra-src` root is written";

    private static final String NO_PROTOS_ROW = "the protobuf plugin's output, which this module has no `.proto`"
            + " sources to fill, so no `extra-src` root is written";

    /** Maven's proto root when the POM names none; the {@code [protobuf]} preset's own default is {@code proto}. */
    private static final String DEFAULT_SRC = "src/main/proto";

    private static final String PRESET_SRC = "proto";
    private static final String DEFAULT_OUTPUT = "target/generated-sources/protobuf";
    private static final String PROTOBUF_GROUP = "com.google.protobuf";
    private static final String PROTOBUF_JAVA = "protobuf-java";

    /** The {@code <configuration>} children the preset's keys and rows cover; anything else is one row. */
    private static final Set<String> COVERED = Set.of(
            "protocArtifact",
            "protoSourceRoot",
            "outputDirectory",
            "pluginId",
            "pluginArtifact",
            "pluginParameter",
            "excludes");

    private static final Set<String> GOALS = Set.of("compile", "compile-custom");
    private static final String CUSTOM_GOAL = "compile-custom";

    /** The table, null when the module owns no protos, the output roots the plugin fills, the plugins consumed. */
    record Mapped(@Nullable PluginConfig table, Map<String, String> outputRoots, Set<String> consumed) {
        static final Mapped NONE = new Mapped(null, Map.of(), Set.of());
    }

    private ProtobufPlugin() {}

    static Mapped map(Model model, ImportReport.Builder report) {
        Plugin plugin = PluginFacts.plugin(model, ARTIFACT).orElse(null);
        if (plugin == null) return Mapped.NONE;
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        List<Xpp3Dom> configs = PluginFacts.configurations(plugin);
        String declaredSrc = value(configs, "protoSourceRoot");
        String src = declaredSrc == null ? DEFAULT_SRC : SourceTreePlugins.moduleRelative(declaredSrc, baseDir);
        Path protoDir = baseDir == null ? Path.of(src) : baseDir.resolve(src);
        List<String> excludes = excludes(plugin);
        List<Path> protos = baseDir == null ? List.of() : protos(protoDir, excludes);
        Map<String, String> outputRoots = new LinkedHashMap<>();
        String row = protos.isEmpty() ? NO_PROTOS_ROW : ADD_SOURCE_ROW;
        outputRoots.put(DEFAULT_OUTPUT, row);
        String output = value(configs, "outputDirectory");
        if (output != null) outputRoots.put(SourceTreePlugins.moduleRelative(output, baseDir), row);
        if (protos.isEmpty()) return new Mapped(null, outputRoots, Set.of());

        Map<String, Object> values = new LinkedHashMap<>();
        String version = version(model, configs, report);
        if (version != null) values.put("version", version);
        if (!PRESET_SRC.equals(src)) values.put("src", src);
        if (!excludes.isEmpty()) values.put("exclude", excludes);
        ReplacerPlugin.Mapped replacer = ReplacerPlugin.map(model, mainOutputRoots(plugin, baseDir), baseDir, report);
        if (!replacer.replace().isEmpty()) values.put("replace", replacer.replace());
        Map<String, Map<String, Object>> plugins = protocPlugins(plugin, configs, report);
        if (!plugins.isEmpty()) values.put(PluginConfig.ENTRIES, plugins);
        reportOtherGoals(plugin, report);
        reportUncovered(configs, report);
        Set<String> consumed = replacer.consumed() ? Set.of(ReplacerPlugin.ARTIFACT) : Set.of();
        return new Mapped(new PluginConfig("protobuf", values), outputRoots, consumed);
    }

    /**
     * The protoc release: {@code <protocArtifact>}'s version segment ({@code g:a:v:exe:classifier}),
     * else the protobuf-java dependency's, whose release protoc matches. Neither is a Tier-3 row,
     * since {@code [protobuf] version} is required and pins protoc in the lockfile.
     */
    private static @Nullable String version(Model model, List<Xpp3Dom> configs, ImportReport.Builder report) {
        String artifact = value(configs, "protocArtifact");
        if (artifact != null) {
            String[] parts = artifact.split(":");
            String version = parts.length >= 3 ? PluginFacts.usable(parts[2]) : null;
            if (version != null) return version;
        }
        for (Dependency d : model.getDependencies()) {
            if (PROTOBUF_GROUP.equals(d.getGroupId()) && PROTOBUF_JAVA.equals(d.getArtifactId())) {
                String version = PluginFacts.usable(d.getVersion());
                if (version != null) return version;
            }
        }
        String managed = PluginFacts.managedVersion(model, PROTOBUF_GROUP, PROTOBUF_JAVA);
        if (managed != null) return managed;
        report.error("`" + ARTIFACT + "` names no resolvable `<protocArtifact>` and the POM no protobuf-java"
                + " version, so `[protobuf]` was written without `version`; set it to the protoc release to run"
                + " (the protobuf-java release the module compiles against).");
        return null;
    }

    /**
     * The protoc plugin {@code compile-custom} runs, as the {@code [protobuf.<pluginId>]} entry:
     * {@code <pluginArtifact>} is {@code group:artifact:version:exe:classifier}, whose first three
     * segments are {@code plugin} (the host classifier is the build's, not the POM's). A
     * configured plugin no {@code compile-custom} execution runs is inert under Maven too, so it
     * writes nothing; one the import cannot name is a row that says what to write.
     */
    private static Map<String, Map<String, Object>> protocPlugins(
            Plugin plugin, List<Xpp3Dom> configs, ImportReport.Builder report) {
        boolean custom = plugin.getExecutions().stream()
                .anyMatch(execution -> execution.getGoals().contains(CUSTOM_GOAL));
        if (!custom) return Map.of();
        String pluginId = value(configs, "pluginId");
        String artifact = value(configs, "pluginArtifact");
        String coordinate = artifact == null ? null : gav(artifact);
        if (pluginId == null || coordinate == null) {
            String name = pluginId == null ? "a" : "the `" + pluginId + "`";
            String table = "[protobuf." + (pluginId == null ? "<id>" : pluginId) + "]";
            report.error("`" + ARTIFACT + "` `" + CUSTOM_GOAL + "` runs " + name + " protoc plugin but names no"
                    + (pluginId == null ? " `<pluginId>`" : " resolvable `<pluginArtifact>`") + ", so no `" + table
                    + "` entry was written; add it with `plugin = \"group:artifact:version\"` naming the plugin"
                    + " executable (protoc's `--" + (pluginId == null ? "<id>" : pluginId) + "_out`).");
            return Map.of();
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("plugin", coordinate);
        String parameter = value(configs, "pluginParameter");
        if (parameter != null) {
            List<String> options = new ArrayList<>();
            for (String option : parameter.split(",")) {
                if (!option.isBlank()) options.add(option.trim());
            }
            if (!options.isEmpty()) entry.put("options", options);
        }
        Map<String, Map<String, Object>> plugins = new LinkedHashMap<>();
        plugins.put(pluginId, entry);
        return plugins;
    }

    /** {@code group:artifact:version} out of a plugin artifact coordinate, null when a segment is missing. */
    private static @Nullable String gav(String artifact) {
        String[] parts = artifact.split(":");
        if (parts.length < 3) return null;
        for (int i = 0; i < 3; i++) if (PluginFacts.usable(parts[i]) == null) return null;
        return parts[0].trim() + ":" + parts[1].trim() + ":" + parts[2].trim();
    }

    private static void reportOtherGoals(Plugin plugin, ImportReport.Builder report) {
        Set<String> others = new LinkedHashSet<>();
        for (PluginExecution execution : plugin.getExecutions()) {
            for (String goal : execution.getGoals()) if (!GOALS.contains(goal)) others.add("`" + goal + "`");
        }
        if (others.isEmpty()) return;
        report.warning("`" + ARTIFACT + "` goals " + String.join(", ", others) + " were not imported; `[protobuf]`"
                + " is the `compile` goal's Java codegen over the main protos.");
    }

    /** Every configuration element the preset has no key or row for, as one row. */
    private static void reportUncovered(List<Xpp3Dom> configs, ImportReport.Builder report) {
        Set<String> names = new LinkedHashSet<>();
        for (Xpp3Dom config : configs) {
            for (Xpp3Dom child : config.getChildren()) {
                if (!COVERED.contains(child.getName())) names.add("`<" + child.getName() + ">`");
            }
        }
        if (names.isEmpty()) return;
        report.warning("`" + ARTIFACT + "` " + String.join(", ", names) + " have no `[protobuf]` key; the preset"
                + " runs protoc with `version`, `src`, `lite` and `kotlin`, and one `[protobuf.<id>]` entry per"
                + " protoc plugin.");
    }

    /**
     * Where the main protos' Java lands: the plugin's default output, and the {@code
     * <outputDirectory>} of its own configuration or of an execution running a main goal — never a
     * {@code test-compile} execution's, whose output the table does not cover.
     */
    private static Set<String> mainOutputRoots(Plugin plugin, @Nullable Path baseDir) {
        Set<String> roots = new LinkedHashSet<>();
        roots.add(DEFAULT_OUTPUT);
        List<Xpp3Dom> configs = new ArrayList<>();
        if (plugin.getConfiguration() instanceof Xpp3Dom dom) configs.add(dom);
        for (PluginExecution execution : plugin.getExecutions()) {
            boolean main = execution.getGoals().stream().anyMatch(GOALS::contains);
            if (main && execution.getConfiguration() instanceof Xpp3Dom dom) configs.add(dom);
        }
        for (Xpp3Dom config : configs) {
            String output = PluginFacts.child(config, "outputDirectory");
            if (output != null) roots.add(SourceTreePlugins.moduleRelative(output, baseDir));
        }
        return roots;
    }

    /**
     * The {@code <excludes>} the main protos compile under: the plugin's own configuration and
     * that of every execution running the {@code compile} or {@code compile-custom} goal, in
     * declaration order without repeats. Each is a root-relative glob as {@code [protobuf] exclude}
     * reads it.
     */
    private static List<String> excludes(Plugin plugin) {
        List<Xpp3Dom> configs = new ArrayList<>();
        if (plugin.getConfiguration() instanceof Xpp3Dom dom) configs.add(dom);
        for (PluginExecution execution : plugin.getExecutions()) {
            boolean main = execution.getGoals().stream().anyMatch(GOALS::contains);
            if (main && execution.getConfiguration() instanceof Xpp3Dom dom) configs.add(dom);
        }
        Set<String> excludes = new LinkedHashSet<>();
        for (Xpp3Dom config : configs) {
            Xpp3Dom declared = config.getChild("excludes");
            if (declared == null) continue;
            for (Xpp3Dom exclude : declared.getChildren()) {
                String glob = PluginFacts.usable(exclude.getValue());
                if (glob != null) excludes.add(glob.trim());
            }
        }
        return List.copyOf(excludes);
    }

    /**
     * The {@code .proto} files under {@code dir} no glob of {@code excludes} names, sorted; empty
     * when it is not a directory. A glob matches the file's path relative to {@code dir}, and one
     * starting {@code **&#47;} matches at the root too, as Maven's {@code <excludes>} do.
     */
    private static List<Path> protos(Path dir, List<String> excludes) {
        List<Path> protos = new ArrayList<>();
        if (!Files.isDirectory(dir)) return protos;
        List<PathMatcher> excluded = new ArrayList<>();
        for (String glob : excludes) {
            excluded.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
            if (glob.startsWith("**/"))
                excluded.add(FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3)));
        }
        try {
            PathUtil.forEachRegularFile(dir, (path, attrs) -> {
                if (!path.getFileName().toString().endsWith(".proto")) return;
                Path relative = dir.relativize(path);
                for (PathMatcher matcher : excluded) if (matcher.matches(relative)) return;
                protos.add(path);
            });
        } catch (IOException e) {
            // An unreadable proto tree owns no protos the import can see; the plugin row still names it.
        }
        protos.sort(null);
        return protos;
    }

    /** The last usable value of {@code name} across {@code configs}, so an execution's wins over the plugin's. */
    private static @Nullable String value(List<Xpp3Dom> configs, String name) {
        String found = null;
        for (Xpp3Dom config : configs) {
            String v = PluginFacts.child(config, name);
            if (v != null) found = v;
        }
        return found;
    }
}
