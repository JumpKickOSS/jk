// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.runtime.base.DokkaResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The exact pins a manifest's tool tables carry, read the way the engine reads them: {@code
 * [dokka] version} pins {@link DokkaResolver#CLI}, and every {@code [[contribute.step-dependency]]}
 * of an active plugin whose coordinate takes its version from the plugin's table — {@code
 * ${config.<key>}} in the version slot ({@code [protobuf] version} → {@code
 * com.google.protobuf:protoc}), or a {@code ${entry.<key>}} coordinate on each {@code
 * [<table>.<name>]} entry ({@code [protobuf.<id>] plugin}, {@code [generate.<name>] tool} and
 * {@code unpack}). A floating selector is no pin and is left to the relock.
 */
final class ToolPins {

    private ToolPins() {}

    /** {@code ${config.<key>}} filling a coordinate's version slot. */
    private static final Pattern CONFIG_KEY = Pattern.compile("\\$\\{config\\.([A-Za-z0-9_-]+)}");

    /** {@code ${entry.<key>}} as a coordinate's whole {@code group:artifact:version} head. */
    private static final Pattern ENTRY_KEY = Pattern.compile("\\$\\{entry\\.([A-Za-z0-9_-]+)}");

    /**
     * One pin: {@code key} of {@code [table]} holds {@code literal}, which pins {@code module} at
     * exactly {@code version}; {@code handle} is what {@code jk update <handle>} selects it by — the
     * table for a plain table, the entry name for a sub-table.
     */
    record ToolPin(String table, String key, String handle, String module, String version, String literal) {

        /** The key's string with the pin moved to {@code to}: the version alone, or the coordinate's version slot. */
        String rewritten(String to) {
            return literal.equals(version) ? to : literal.substring(0, literal.lastIndexOf(':') + 1) + to;
        }
    }

    /**
     * Every tool pin {@code build} declares. {@code text} is the manifest as written, consulted for
     * the {@code [dokka]} header because the parsed table defaults when absent.
     */
    static List<ToolPin> of(JkBuild build, String text) {
        List<ToolPin> out = new ArrayList<>();
        if (text.lines().anyMatch(l -> l.strip().equals("[dokka]"))) {
            String raw = build.build().dokka().version().raw().trim();
            exact(raw).ifPresent(v -> out.add(new ToolPin("dokka", "version", "dokka", DokkaResolver.CLI, v, raw)));
        }
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(null, build.plugins())) {
            PluginConfig config = build.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            for (PluginDescriptor.StepDependency sd : manifest.contributions().stepDependencies()) {
                String template = sd.coordinate();
                if (template == null) continue;
                if (sd.perEntry()) {
                    perEntry(manifest.table(), template, config, out);
                } else {
                    plain(manifest.table(), template, config, out);
                }
            }
        }
        return out;
    }

    /** {@code group:artifact:${config.<key>}…}: the table's own key pins a literal module. */
    private static void plain(String table, String template, PluginConfig config, List<ToolPin> out) {
        String[] parts = template.split(":");
        if (parts.length < 3 || parts[0].contains("${") || parts[1].contains("${")) return;
        Matcher m = CONFIG_KEY.matcher(parts[2]);
        if (!m.matches()) return;
        String key = m.group(1);
        String raw = config.stringOpt(key).orElse(null);
        if (raw == null) return;
        exact(raw).ifPresent(v -> out.add(new ToolPin(table, key, table, parts[0] + ":" + parts[1], v, raw)));
    }

    /** {@code ${entry.<key>}…}: each entry's key holds a {@code group:artifact:version} coordinate. */
    private static void perEntry(String table, String template, PluginConfig config, List<ToolPin> out) {
        int colon = template.indexOf(':');
        Matcher m = ENTRY_KEY.matcher(colon < 0 ? template : template.substring(0, colon));
        if (!m.matches()) return;
        String key = m.group(1);
        for (Map.Entry<String, Map<String, Object>> entry : config.entries().entrySet()) {
            if (!(entry.getValue().get(key) instanceof String gav)) continue;
            String[] parts = gav.split(":", 3);
            if (parts.length < 3 || parts[0].isBlank() || parts[1].isBlank()) continue;
            String module = parts[0] + ":" + parts[1];
            exact(parts[2].trim())
                    .ifPresent(v ->
                            out.add(new ToolPin(table + "." + entry.getKey(), key, entry.getKey(), module, v, gav)));
        }
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
