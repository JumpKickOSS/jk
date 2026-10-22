// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.JkBuildEditor;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.LanguageRuntimes;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.plugin.manifest.Interpolation;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.runtime.base.DokkaResolver;
import cc.jumpkick.runtime.base.KspResolver;
import cc.jumpkick.scala.ScalaResolver;
import cc.jumpkick.util.MinimalToml;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The exact pins a manifest's language keys and framework and tool tables carry, read the way the
 * engine reads them: the root {@code kotlin}, {@code groovy} and {@code scala} keys pin their
 * compilers, {@code [dokka] version} pins {@link DokkaResolver#CLI}, {@code [build] ksp-version}
 * pins {@link KspResolver#KSP_AA_MODULE}, every {@code [[contribute.platform-dependency]]} of an active plugin
 * whose version comes from its table ({@code [spring-boot] version} → {@code
 * spring-boot-dependencies}, {@code [micronaut] version} → {@code micronaut-platform}), and every
 * {@code [[contribute.step-dependency]]} of an active plugin whose coordinate takes its version
 * from the plugin's table — {@code
 * ${config.<key>}} in the version slot ({@code [protobuf] version} → {@code
 * com.google.protobuf:protoc}), a {@code ${entry.<key>}} coordinate on each {@code
 * [<table>.<name>]} entry ({@code [protobuf.<id>] plugin}, {@code [generate.<name>] tool} and
 * {@code unpack}), or {@code ${entry.<key>}} in the version slot of a per-entry coordinate. The
 * group and artifact segments are templates too, filled from the table and the entry through
 * {@link Interpolation} as the step's own coordinate is, so a plugin that templates them pins the
 * module they name. Only a key the manifest writes is a pin — a schema default moves with jk — and
 * a floating selector is no pin and is left to the relock. {@code jk update} moves exactly these
 * pins and {@code jk outdated} reports exactly these.
 */
final class ToolPins {

    private ToolPins() {}

    /** {@code ${config.<key>}} filling a coordinate's version slot. */
    private static final Pattern CONFIG_KEY = Pattern.compile("\\$\\{config\\.([A-Za-z0-9_-]+)}");

    /** {@code ${entry.<key>}} as a coordinate's whole {@code group:artifact:version} head, or its version slot. */
    private static final Pattern ENTRY_KEY = Pattern.compile("\\$\\{entry\\.([A-Za-z0-9_-]+)}");

    /** The Kotlin compiler the root {@code kotlin} key pins. */
    private static final String KOTLIN_COMPILER = "org.jetbrains.kotlin:kotlin-compiler-embeddable";

    /**
     * One pin: {@code key} of {@code [table]} (a root key when {@code table} is empty) holds {@code
     * literal}, which pins {@code module} at exactly {@code version}; {@code handle} is what {@code jk
     * update <handle>} selects it by — the key for a root key, the table for a plain table, the entry
     * name for a sub-table.
     */
    record ToolPin(String table, String key, String handle, String module, String version, String literal) {

        /** Where the pin is written: {@code kotlin}, {@code dokka.version}, {@code protobuf.grpc-java.plugin}. */
        String path() {
            return table.isEmpty() ? key : table + "." + key;
        }

        /** The key's string with the pin moved to {@code to}: the version alone, or the coordinate's version slot. */
        String rewritten(String to) {
            return literal.equals(version) ? to : literal.substring(0, literal.lastIndexOf(':') + 1) + to;
        }

        /**
         * {@code text} with this pin moved to {@code to}, keeping the line's spacing and comment.
         *
         * @throws IllegalStateException when the manifest does not write the key as a string
         */
        String rewrite(String text, String to) {
            return table.isEmpty()
                    ? JkBuildEditor.setRootScalar(text, key, MinimalToml.quote(rewritten(to)))
                    : JkBuildEditor.setTableString(text, table, key, rewritten(to));
        }

        /** True when {@code text} writes this key itself, rather than the table's schema defaulting it. */
        boolean writtenIn(String text) {
            if (table.isEmpty()) return true; // a root key parses to null unless written
            try {
                rewrite(text, version);
                return true;
            } catch (IllegalStateException absent) {
                return false;
            }
        }
    }

    /** Every pin {@code build} declares; {@code text} is its manifest as written. */
    static List<ToolPin> of(JkBuild build, String text) {
        List<ToolPin> out = new ArrayList<>();
        language("kotlin", build.project().kotlin(), KOTLIN_COMPILER, out);
        language("groovy", build.project().groovy(), LanguageRuntimes.GROOVY, out);
        language("scala", build.project().scala(), ScalaResolver.COMPILER_MODULE, out);
        String dokka = build.build().dokka().version().raw().trim();
        exact(dokka).ifPresent(v -> out.add(new ToolPin("dokka", "version", "dokka", DokkaResolver.CLI, v, dokka)));
        String ksp = build.build().kspVersion();
        if (ksp != null) out.add(new ToolPin("build", "ksp-version", "ksp", KspResolver.KSP_AA_MODULE, ksp, ksp));
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(null, build.plugins())) {
            PluginConfig config = build.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            for (PluginDescriptor.PlatformDependency pd :
                    manifest.contributions().platformDependencies()) {
                plain(manifest.table(), pd.coordinate(), config, build.project(), out);
            }
            for (PluginDescriptor.StepDependency sd : manifest.contributions().stepDependencies()) {
                String template = sd.coordinate();
                if (template == null) continue;
                if (sd.perEntry()) {
                    perEntry(manifest.table(), template, config, build.project(), out);
                } else {
                    plain(manifest.table(), template, config, build.project(), out);
                }
            }
        }
        return out.stream().filter(p -> p.writtenIn(text)).toList();
    }

    /** A root language key's exact pin on its compiler. */
    private static void language(String key, @Nullable VersionSelector selector, String module, List<ToolPin> out) {
        if (selector instanceof VersionSelector.Exact e) {
            out.add(new ToolPin("", key, key, module, e.version(), e.version()));
        }
    }

    /** {@code <group>:<artifact>:${config.<key>}…}: the table's own key pins the module the head names. */
    private static void plain(String table, String template, PluginConfig config, Project project, List<ToolPin> out) {
        String[] parts = template.split(":");
        if (parts.length < 3) return;
        Matcher m = CONFIG_KEY.matcher(versionSlot(parts[2]));
        if (!m.matches()) return;
        String key = m.group(1);
        String raw = config.stringOpt(key).orElse(null);
        String module = raw == null ? null : module(parts[0], parts[1], config, project, null);
        if (raw == null || module == null) return;
        exact(raw).ifPresent(v -> out.add(new ToolPin(table, key, table, module, v, raw)));
    }

    /**
     * A per-entry declaration, once per {@code [<table>.<name>]} entry: {@code ${entry.<key>}…} as
     * the whole head, where the entry's key holds a {@code group:artifact:version} coordinate; or
     * {@code <group>:<artifact>:${entry.<key>}…}, where it holds the version of the module the
     * head names for that entry.
     */
    private static void perEntry(
            String table, String template, PluginConfig config, Project project, List<ToolPin> out) {
        String[] parts = template.split(":");
        Matcher head = ENTRY_KEY.matcher(parts[0]);
        boolean wholeHead = head.matches();
        String key;
        if (wholeHead) {
            key = head.group(1);
        } else {
            if (parts.length < 3) return;
            Matcher slot = ENTRY_KEY.matcher(versionSlot(parts[2]));
            if (!slot.matches()) return;
            key = slot.group(1);
        }
        for (Map.Entry<String, Map<String, Object>> entry : config.entries().entrySet()) {
            if (!(entry.getValue().get(key) instanceof String literal)) continue;
            String name = entry.getKey();
            String module;
            String selector;
            if (wholeHead) {
                String[] gav = literal.split(":", 3);
                if (gav.length < 3 || gav[0].isBlank() || gav[1].isBlank()) continue;
                module = gav[0] + ":" + gav[1];
                selector = gav[2].trim();
            } else {
                module = module(parts[0], parts[1], config, project, new Interpolation.Entry(name, entry.getValue()));
                selector = literal.trim();
            }
            if (module == null) continue;
            exact(selector).ifPresent(v -> out.add(new ToolPin(table + "." + name, key, name, module, v, literal)));
        }
    }

    /**
     * The {@code group:artifact} the head's two segments name once their variables are filled from
     * the table (and the entry in scope); null when one names a value the table leaves unset.
     */
    private static @Nullable String module(
            String group, String artifact, PluginConfig config, Project project, Interpolation.@Nullable Entry entry) {
        try {
            String g = Interpolation.resolve(group, config, project, null, entry);
            String a = Interpolation.resolve(artifact, config, project, null, entry);
            return g.isBlank() || a.isBlank() ? null : g + ":" + a;
        } catch (JkBuildParseException unset) {
            Log.debug("ToolPins.module: a head segment names a value the table leaves unset", unset);
            return null;
        }
    }

    /** A coordinate's version segment without its {@code !type} suffix ({@code ${config.version}!zip}). */
    private static String versionSlot(String segment) {
        int bang = segment.indexOf('!');
        return bang < 0 ? segment : segment.substring(0, bang);
    }

    /** The version an exact selector pins; empty for a floating one or text that is no selector. */
    private static Optional<String> exact(String raw) {
        try {
            return VersionSelector.parse(raw) instanceof VersionSelector.Exact e
                    ? Optional.of(e.version())
                    : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
