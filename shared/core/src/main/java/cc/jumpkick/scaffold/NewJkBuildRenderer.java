// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.util.MinimalToml;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a starter {@code jk.toml} from {@link NewInputs} (name-as-key dependency tables).
 *
 * <p>Every string value goes through {@link MinimalToml#quote}, so a project name, group or main
 * class containing a quote or a backslash — a Windows path in {@code main}, say — still produces
 * parseable TOML.
 */
public final class NewJkBuildRenderer {

    /** Compiler version selector: first {@code jk lock} pins the current stable. */
    private static final String LATEST = "latest";

    private NewJkBuildRenderer() {}

    public static String render(NewInputs inputs) {
        var sb = new StringBuilder();
        sb.append("name     = ").append(MinimalToml.quote(inputs.name())).append('\n');
        sb.append("group    = ").append(MinimalToml.quote(inputs.group())).append('\n');
        sb.append("version  = \"0.1.0\"\n");
        sb.append("jdk      = ").append(MinimalToml.quote(inputs.jdk())).append('\n');
        switch (inputs.lang()) {
            case JAVA -> sb.append("java     = ").append(inputs.javaRelease()).append('\n');
            case KOTLIN -> sb.append("kotlin   = \"").append(LATEST).append("\"\n");
            case GROOVY -> sb.append("groovy   = \"").append(LATEST).append("\"\n");
            case SCALA -> sb.append("scala    = \"").append(LATEST).append("\"\n");
        }
        inputs.kotlinModuleName()
                .ifPresent(m ->
                        sb.append("module   = ").append(MinimalToml.quote(m)).append('\n'));

        if (!inputs.plugin() && (inputs.main().isPresent() || inputs.assembly())) {
            sb.append("\n[application]\n");
            if (inputs.main().isPresent()) {
                sb.append("main       = ")
                        .append(MinimalToml.quote(inputs.main().get()))
                        .append('\n');
            }
            if (inputs.assembly()) {
                sb.append("assembly   = true\n"); // aligns with `main       =` above
            }
        }
        if (inputs.nativeImage()) {
            sb.append("\n[native]\n");
            sb.append("enabled    = \"always\"\n");
        }

        if (inputs.plugin()) {
            // The SDK the plugin compiles against. A `main` dep, NOT `provided`: the worker forks
            // as `java -jar`, so jk-plugin-sdk must be shaded INTO the fat jar, not merely on the
            // compile classpath. The published version is owned by shared/plugin-sdk/build.gradle.kts;
            // PluginSdkScaffoldVersionTest fails if this copy drifts from it.
            sb.append("\n[dependencies]\n");
            sb.append("jk-plugin-sdk = { group = \"cc.jumpkick\", version = \"0.1.0\" }\n");
            return sb.toString();
        }

        var picks = resolvePicks(inputs.deps());
        if (picks.isEmpty()) return sb.toString();

        // Emit scopes in the order they show up in the curated catalog: the
        // wizard's display order should roughly match the rendered file.
        renderScope(sb, "main", picks.getOrDefault("main", List.of()));
        renderScope(sb, "processor", picks.getOrDefault("processor", List.of()));
        renderScope(sb, "provided", picks.getOrDefault("provided", List.of()));
        renderScope(sb, "test", picks.getOrDefault("test", List.of()));
        return sb.toString();
    }

    private static void renderScope(StringBuilder sb, String scope, List<NewScaffolder.CuratedEntry> entries) {
        if (entries.isEmpty()) return;
        sb.append('\n');
        String sectionHeader = scope.equals("main") ? "dependencies" : scope + "-dependencies";
        sb.append("[").append(sectionHeader).append("]\n");
        for (var e : entries) {
            sb.append(formatEntry(e)).append('\n');
        }
    }

    /**
     * Render a single curated dep. The short name is the artifactId (the part after the colon in
     * {@code group:artifact}).
     *
     * <p>When that short name resolves through the bundled library catalog to this exact coordinate,
     * emit the Cargo-style one-liner {@code name = "latest"} — the catalog supplies group/artifact
     * and the resolver floats to the newest release. Otherwise fall back to the explicit inline table
     * {@code { group = "...", version = "..." }} using the curated major (bare-string version is
     * caret-floating per the v1 default — {@code ^1} → 1.x.x).
     */
    private static String formatEntry(NewScaffolder.CuratedEntry e) {
        int colon = e.coord().indexOf(':');
        String group = e.coord().substring(0, colon);
        String artifact = e.coord().substring(colon + 1);
        var hit = LibraryCatalog.bundled().lookup(artifact).orElse(null);
        if (hit != null && hit.group().equals(group) && hit.artifact().equals(artifact)) {
            return artifact + " = \"latest\"";
        }
        return artifact + " = { group = " + MinimalToml.quote(group) + ", version = " + MinimalToml.quote(e.version())
                + " }";
    }

    /**
     * Group selected dep ids by scope. Returns a nested map of scope -> curated entries, preserving
     * insertion order so generated files have a stable shape and de-duping by short name (artifactId)
     * within a scope.
     */
    private static Map<String, List<NewScaffolder.CuratedEntry>> resolvePicks(List<String> deps) {
        Map<String, Map<String, NewScaffolder.CuratedEntry>> byScope = new LinkedHashMap<>();
        for (var id : deps) {
            if (id == null || id.isBlank()) continue;
            var curated = NewScaffolder.CURATED_DEPS.get(id);
            if (curated != null) {
                for (var e : curated) {
                    String shortName = e.coord().substring(e.coord().indexOf(':') + 1);
                    byScope.computeIfAbsent(e.scope(), _ -> new LinkedHashMap<>())
                            .putIfAbsent(shortName, e);
                }
                continue;
            }
            // Library catalog short name → main dep floating to latest.
            var hit = LibraryCatalog.bundled().lookup(id.strip()).orElse(null);
            if (hit != null) {
                String coord = hit.group() + ":" + hit.artifact();
                byScope.computeIfAbsent("main", _ -> new LinkedHashMap<>())
                        .putIfAbsent(hit.artifact(), new NewScaffolder.CuratedEntry(coord, "latest", "main"));
                continue;
            }
            // Free-form group:artifact or group:artifact:version
            String raw = id.strip();
            String[] parts = raw.split(":");
            if (parts.length == 2 || parts.length == 3) {
                String group = parts[0].strip();
                String artifact = parts[1].strip();
                String version = parts.length == 3 ? parts[2].strip() : "latest";
                if (!group.isEmpty() && !artifact.isEmpty()) {
                    byScope.computeIfAbsent("main", _ -> new LinkedHashMap<>())
                            .putIfAbsent(
                                    artifact, new NewScaffolder.CuratedEntry(group + ":" + artifact, version, "main"));
                }
            }
        }
        Map<String, List<NewScaffolder.CuratedEntry>> out = new LinkedHashMap<>();
        byScope.forEach((scope, entries) -> out.put(scope, List.copyOf(entries.values())));
        return out;
    }
}
