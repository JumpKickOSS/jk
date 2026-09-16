// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.PluginConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * {@code protobuf-maven-plugin} is the {@code [protobuf]} preset on a module that owns
 * {@code .proto} sources: {@code <protocArtifact>}'s version is {@code version} (the protobuf-java
 * dependency's when the POM names no protoc), {@code <protoSourceRoot>} is {@code src}. The
 * plugin's output under {@code target/generated-sources/protobuf} is the preset's contribution, so
 * a build-helper root inside it is not written as an {@code extra-src}; a module the plugin reaches
 * by inheritance without protos of its own gets neither the table nor the root. A protoc plugin the
 * {@code compile-custom} goal runs (gRPC's) has no key, so it is a row: Tier 3 when a proto declares
 * a {@code service} the stubs would serve, a warning otherwise.
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
            "protocArtifact", "protoSourceRoot", "outputDirectory", "pluginId", "pluginArtifact", "pluginParameter");

    private static final Set<String> GOALS = Set.of("compile", "compile-custom");
    private static final Pattern SERVICE = Pattern.compile("(?m)^\\s*service\\s+\\w+");

    /** The table, null when the module owns no protos, and the output roots the plugin fills. */
    record Mapped(@Nullable PluginConfig table, Map<String, String> outputRoots) {
        static final Mapped NONE = new Mapped(null, Map.of());
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
        List<Path> protos = baseDir == null ? List.of() : protos(protoDir);
        Map<String, String> outputRoots = new LinkedHashMap<>();
        String row = protos.isEmpty() ? NO_PROTOS_ROW : ADD_SOURCE_ROW;
        outputRoots.put(DEFAULT_OUTPUT, row);
        String output = value(configs, "outputDirectory");
        if (output != null) outputRoots.put(SourceTreePlugins.moduleRelative(output, baseDir), row);
        if (protos.isEmpty()) return new Mapped(null, outputRoots);

        Map<String, Object> values = new LinkedHashMap<>();
        String version = version(model, configs, report);
        if (version != null) values.put("version", version);
        if (!PRESET_SRC.equals(src)) values.put("src", src);
        reportProtocPlugin(plugin, configs, protoDir, protos, src, report);
        reportOtherGoals(plugin, report);
        reportUncovered(configs, report);
        return new Mapped(new PluginConfig("protobuf", values), outputRoots);
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

    /** The protoc plugin {@code compile-custom} runs; gRPC's stubs are needed when a proto declares a service. */
    private static void reportProtocPlugin(
            Plugin plugin,
            List<Xpp3Dom> configs,
            Path protoDir,
            List<Path> protos,
            String src,
            ImportReport.Builder report) {
        String pluginId = value(configs, "pluginId");
        String pluginArtifact = value(configs, "pluginArtifact");
        boolean custom = plugin.getExecutions().stream()
                .anyMatch(execution -> execution.getGoals().contains("compile-custom"));
        if (pluginId == null && pluginArtifact == null && !custom) return;
        String name = pluginId != null ? "`" + pluginId + "`" : "a";
        String coordinate = pluginArtifact != null ? " (" + pluginArtifact + ")" : "";
        List<String> services = declaringServices(protoDir, protos, src);
        String text = "`" + ARTIFACT + "` runs " + name + " protoc plugin" + coordinate + " through"
                + " `compile-custom`; `[protobuf]` has no key for a protoc plugin, so its output is not generated"
                + " under jk";
        if (services.isEmpty()) {
            report.warning(text + ", and no `.proto` under `" + src + "` declares a `service`, so nothing here"
                    + " needs it.");
            return;
        }
        report.error(text + ". " + String.join(", ", services) + " declare a `service`, so a source importing"
                + " the generated stubs does not compile; check the stubs in under a source root of their own,"
                + " or keep the module under `jk mvn`.");
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
                + " runs protoc with `version`, `src`, `lite` and `kotlin`.");
    }

    /** The {@code .proto} files under {@code dir}, sorted; empty when it is not a directory. */
    private static List<Path> protos(Path dir) {
        List<Path> protos = new ArrayList<>();
        if (!Files.isDirectory(dir)) return protos;
        try {
            PathUtil.forEachRegularFile(dir, (path, attrs) -> {
                if (path.getFileName().toString().endsWith(".proto")) protos.add(path);
            });
        } catch (IOException e) {
            // An unreadable proto tree owns no protos the import can see; the plugin row still names it.
        }
        protos.sort(null);
        return protos;
    }

    /** The protos under {@code protoDir} that declare a {@code service}, as backticked module-relative paths. */
    private static List<String> declaringServices(Path protoDir, List<Path> protos, String src) {
        List<String> names = new ArrayList<>();
        for (Path proto : protos) {
            try {
                if (!SERVICE.matcher(Files.readString(proto, StandardCharsets.UTF_8))
                        .find()) continue;
            } catch (IOException e) {
                continue;
            }
            names.add("`" + src + "/" + protoDir.relativize(proto).toString().replace('\\', '/') + "`");
        }
        return names;
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
