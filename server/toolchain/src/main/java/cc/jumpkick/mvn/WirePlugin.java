// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import java.nio.file.Path;
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
 * {@code wire-maven-plugin} (the {@code de.m3y.maven} resurrection or Square's own) is the
 * {@code [generate.wire]} recipe: {@code com.squareup.wire:wire-compiler} at the module's
 * wire-runtime version with the compiler class as {@code main}, {@code <protoSourceDirectory>} as
 * the proto glob and {@code --proto_path}, {@code <includes>} / {@code <excludes>} as the compiler's
 * flags, a {@code generate-test-sources} binding as a test-sources contribution. A
 * {@code maven-dependency-plugin} execution that unpacks a dependency into the proto directory —
 * zipkin's shape, since the plugin reads no protos from jars — is the recipe's {@code unpack}, and
 * that plugin is then imported. The {@code <generatedSourceDirectory>} is the recipe's own
 * contribution, so a build-helper root inside it is not written.
 */
final class WirePlugin {

    static final String ARTIFACT = "wire-maven-plugin";
    static final String DEPENDENCY_PLUGIN = "maven-dependency-plugin";
    static final String ENTRY = "wire";
    static final String COMPILER = "com.squareup.wire:wire-compiler";
    static final String COMPILER_MAIN = "com.squareup.wire.WireCompiler";

    /** What an {@code add-source} root inside the output is, for the build-helper row. */
    static final String ADD_SOURCE_ROW = "the wire recipe's output; `[generate.wire]` folds the generated Java"
            + " into the compile itself, so no `extra-src` root is written";

    private static final String DEFAULT_SRC = "src/main/proto";
    private static final String DEFAULT_OUTPUT = "target/generated-sources/wire";
    private static final String WIRE_GROUP = "com.squareup.wire";
    private static final Set<String> UNPACK_GOALS = Set.of("unpack", "unpack-dependencies");

    /** The entry's values (null without the plugin), the output root it fills, the plugins it consumed. */
    record Mapped(@Nullable Map<String, Object> entry, Map<String, String> outputRoots, Set<String> consumed) {
        static final Mapped NONE = new Mapped(null, Map.of(), Set.of());
    }

    private WirePlugin() {}

    static Mapped map(Model model, ImportReport.Builder report) {
        Plugin plugin = PluginFacts.plugin(model, ARTIFACT).orElse(null);
        if (plugin == null) return Mapped.NONE;
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        List<Xpp3Dom> configs = PluginFacts.configurations(plugin);
        String declaredSrc = value(configs, "protoSourceDirectory");
        String src = declaredSrc == null ? DEFAULT_SRC : SourceTreePlugins.moduleRelative(declaredSrc, baseDir);
        String declaredOut = value(configs, "generatedSourceDirectory");
        String out = declaredOut == null ? DEFAULT_OUTPUT : SourceTreePlugins.moduleRelative(declaredOut, baseDir);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("tool", COMPILER + ":" + compilerVersion(model, report));
        entry.put("main", COMPILER_MAIN);
        List<String> args = new ArrayList<>();
        Set<String> consumed = new LinkedHashSet<>();
        String unpack = unpackedInto(model, src, baseDir, report);
        if (unpack != null) {
            entry.put("unpack", unpack);
            args.add("--proto_path=${unpacked}");
            consumed.add(DEPENDENCY_PLUGIN);
        } else {
            entry.put("inputs", List.of(src + "/**/*.proto"));
            args.add("--proto_path=${module.dir}/" + src);
        }
        args.add("--java_out=${out}");
        List<String> includes = patterns(configs, "includes", "include");
        if (!includes.isEmpty()) args.add("--includes=" + String.join(",", includes));
        List<String> excludes = patterns(configs, "excludes", "exclude");
        if (!excludes.isEmpty()) args.add("--excludes=" + String.join(",", excludes));
        entry.put("args", args);
        if (boundToTests(plugin)) entry.put("contributes", "test-sources");
        return new Mapped(entry, Map.of(out, ADD_SOURCE_ROW), consumed);
    }

    /** The module's wire-runtime version — the generated code and the runtime are one release — else {@code latest}. */
    private static String compilerVersion(Model model, ImportReport.Builder report) {
        for (Dependency dep : model.getDependencies()) {
            if (WIRE_GROUP.equals(dep.getGroupId())
                    && dep.getArtifactId() != null
                    && dep.getArtifactId().startsWith("wire-runtime")) {
                String version = PluginFacts.usable(dep.getVersion());
                if (version != null) return version;
            }
        }
        report.warning("`" + ARTIFACT + "` runs a wire-compiler release the plugin chooses, and the module has no"
                + " wire-runtime dependency to take the version from; `[generate.wire] tool` is `" + COMPILER
                + ":latest` — pin the release the runtime you add matches.");
        return "latest";
    }

    /**
     * The dependency a {@code maven-dependency-plugin} unpack execution extracts into {@code src},
     * as {@code group:artifact:version}; null when no execution unpacks there.
     */
    private static @Nullable String unpackedInto(
            Model model, String src, @Nullable Path baseDir, ImportReport.Builder report) {
        Plugin plugin = PluginFacts.plugin(model, DEPENDENCY_PLUGIN).orElse(null);
        if (plugin == null) return null;
        for (PluginExecution execution : plugin.getExecutions()) {
            if (execution.getGoals().stream().noneMatch(UNPACK_GOALS::contains)) continue;
            Xpp3Dom config = execution.getConfiguration() instanceof Xpp3Dom dom ? dom : null;
            String output = PluginFacts.child(config, "outputDirectory");
            if (output == null || !src.equals(SourceTreePlugins.moduleRelative(output, baseDir))) continue;
            List<String> coordinates = unpackedArtifacts(model, config);
            if (coordinates.isEmpty()) {
                report.warning("`" + DEPENDENCY_PLUGIN + "` execution `" + execution.getId() + "` unpacks into `" + src
                        + "`, the wire recipe's proto source, but names no artifact the module depends on;"
                        + " `[generate.wire]` reads the protos from the directory instead.");
                return null;
            }
            if (coordinates.size() > 1) {
                report.warning("`" + DEPENDENCY_PLUGIN + "` execution `" + execution.getId() + "` unpacks "
                        + coordinates.size() + " artifacts into `" + src + "`; `[generate.wire] unpack` names the first"
                        + " (" + coordinates.getFirst() + ") — a further jar is another `[generate.<name>]` entry.");
            }
            return coordinates.getFirst();
        }
        return null;
    }

    /** {@code <includeArtifactIds>} resolved against the module's dependencies, or {@code <artifactItems>}. */
    private static List<String> unpackedArtifacts(Model model, @Nullable Xpp3Dom config) {
        List<String> out = new ArrayList<>();
        if (config == null) return out;
        String ids = PluginFacts.child(config, "includeArtifactIds");
        if (ids != null) {
            for (String id : ids.split(",")) {
                String artifactId = id.trim();
                for (Dependency dep : model.getDependencies()) {
                    String version = PluginFacts.usable(dep.getVersion());
                    if (artifactId.equals(dep.getArtifactId()) && version != null) {
                        out.add(dep.getGroupId() + ":" + dep.getArtifactId() + ":" + version);
                    }
                }
            }
        }
        Xpp3Dom items = config.getChild("artifactItems");
        if (items != null) {
            for (Xpp3Dom item : items.getChildren()) {
                String group = PluginFacts.child(item, "groupId");
                String artifact = PluginFacts.child(item, "artifactId");
                String version = PluginFacts.child(item, "version");
                if (version == null)
                    version = PluginFacts.managedVersion(
                            model, group == null ? "" : group, artifact == null ? "" : artifact);
                if (group != null && artifact != null && version != null)
                    out.add(group + ":" + artifact + ":" + version);
            }
        }
        return out;
    }

    /** True when an execution binds to a test-sources phase. */
    private static boolean boundToTests(Plugin plugin) {
        for (PluginExecution execution : plugin.getExecutions()) {
            String phase = execution.getPhase();
            if (phase != null && phase.startsWith("generate-test")) return true;
        }
        return false;
    }

    /** {@code <includes><include>…</include></includes>} across the configurations, in order. */
    private static List<String> patterns(List<Xpp3Dom> configs, String list, String item) {
        List<String> out = new ArrayList<>();
        for (Xpp3Dom config : configs) {
            Xpp3Dom holder = config.getChild(list);
            if (holder == null) continue;
            for (Xpp3Dom child : holder.getChildren()) {
                if (!item.equals(child.getName())) continue;
                String value = PluginFacts.usable(child.getValue());
                if (value != null && !out.contains(value)) out.add(value);
            }
        }
        return out;
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
