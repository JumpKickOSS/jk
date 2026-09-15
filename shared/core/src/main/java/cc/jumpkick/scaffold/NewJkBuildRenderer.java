// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import cc.jumpkick.config.JkBuildEditor;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Renders a starter {@code jk.toml} from {@link NewInputs} (name-as-key dependency tables). Every
 * library and compiler version written is a number: the newest stable {@link ScaffoldVersions}
 * reports for that coordinate, or the version the caller spelled in a {@code group:artifact:version}
 * pick.
 *
 * <p>Every string value goes through {@link MinimalToml#quote}, so a project name, group or main
 * class containing a quote or a backslash — a Windows path in {@code main}, say — still produces
 * parseable TOML.
 */
public final class NewJkBuildRenderer {

    private NewJkBuildRenderer() {}

    public static String render(NewInputs inputs, ScaffoldVersions versions) throws IOException {
        Lookup lookup = new Lookup(versions);
        var sb = new StringBuilder();
        sb.append("name     = ").append(MinimalToml.quote(inputs.name())).append('\n');
        sb.append("group    = ").append(MinimalToml.quote(inputs.group())).append('\n');
        sb.append("version  = \"0.1.0\"\n");
        sb.append("jdk      = ").append(MinimalToml.quote(inputs.jdk())).append('\n');
        switch (inputs.lang()) {
            case JAVA -> sb.append("java     = ").append(inputs.javaRelease()).append('\n');
            case KOTLIN ->
                sb.append("kotlin   = ")
                        .append(compilerVersion("org.jetbrains.kotlin:kotlin-compiler-embeddable", lookup))
                        .append('\n');
            case GROOVY ->
                sb.append("groovy   = ")
                        .append(compilerVersion("org.apache.groovy:groovy", lookup))
                        .append('\n');
            case SCALA ->
                sb.append("scala    = ")
                        .append(compilerVersion("org.scala-lang:scala3-compiler_3", lookup))
                        .append('\n');
        }
        inputs.kotlinModuleNameOpt()
                .ifPresent(m ->
                        sb.append("module   = ").append(MinimalToml.quote(m)).append('\n'));

        if (!inputs.plugin() && (inputs.mainOpt().isPresent() || inputs.assembly())) {
            sb.append("\n[application]\n");
            if (inputs.mainOpt().isPresent()) {
                sb.append("main       = ")
                        .append(MinimalToml.quote(inputs.mainOpt().get()))
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
            // compile classpath. The SDK rides jk's own release train, so the pin is JkVersion.
            sb.append("\n[dependencies]\n");
            sb.append(JkBuildEditor.renderDependencyEntry(
                            LibraryCatalog.bundled(),
                            "jk-plugin-sdk",
                            "cc.jumpkick",
                            "jk-plugin-sdk",
                            JkVersion.VERSION))
                    .append('\n');
            return sb.toString();
        }

        var picks = resolvePicks(inputs.deps());
        if (picks.isEmpty()) return sb.toString();

        // Emit scopes in the order they show up in the curated catalog: the
        // wizard's display order should roughly match the rendered file.
        renderScope(sb, "main", picks.getOrDefault("main", List.of()), lookup);
        renderScope(sb, "processor", picks.getOrDefault("processor", List.of()), lookup);
        renderScope(sb, "provided", picks.getOrDefault("provided", List.of()), lookup);
        renderScope(sb, "test", picks.getOrDefault("test", List.of()), lookup);
        return sb.toString();
    }

    /** The quoted compiler version: the newest stable release of the language's compiler artifact. */
    private static String compilerVersion(String compilerCoord, Lookup lookup) throws IOException {
        return MinimalToml.quote(lookup.stable(compilerCoord));
    }

    private static void renderScope(StringBuilder sb, String scope, List<Pick> entries, Lookup lookup)
            throws IOException {
        if (entries.isEmpty()) return;
        sb.append('\n');
        String sectionHeader = scope.equals("main") ? "dependencies" : scope + "-dependencies";
        sb.append("[").append(sectionHeader).append("]\n");
        for (var e : entries) {
            sb.append(formatEntry(e, lookup)).append('\n');
        }
    }

    /** A dependency to render: its coordinate, the version the caller spelled (else the newest stable), its scope. */
    private record Pick(String coord, @Nullable String version, String scope) {}

    /**
     * Render one dependency through {@link JkBuildEditor#renderDependencyEntry}, so a scaffold and
     * {@code jk add} spell an entry the same way. The handle is the artifactId.
     */
    private static String formatEntry(Pick e, Lookup lookup) throws IOException {
        int colon = e.coord().indexOf(':');
        String group = e.coord().substring(0, colon);
        String artifact = e.coord().substring(colon + 1);
        String version = e.version() != null ? e.version() : lookup.stable(e.coord());
        return JkBuildEditor.renderDependencyEntry(LibraryCatalog.bundled(), artifact, group, artifact, version);
    }

    /**
     * Group selected dep ids by scope. Returns a nested map of scope -> picks, preserving insertion
     * order so generated files have a stable shape and de-duping by short name (artifactId) within a
     * scope.
     */
    private static Map<String, List<Pick>> resolvePicks(List<String> deps) {
        Map<String, Map<String, Pick>> byScope = new LinkedHashMap<>();
        for (var id : deps) {
            if (id == null || id.isBlank()) continue;
            var curated = NewScaffolder.CURATED_DEPS.get(id);
            if (curated != null) {
                for (var e : curated) {
                    String shortName = e.coord().substring(e.coord().indexOf(':') + 1);
                    byScope.computeIfAbsent(e.scope(), _ -> new LinkedHashMap<>())
                            .putIfAbsent(shortName, new Pick(e.coord(), null, e.scope()));
                }
                continue;
            }
            // Library catalog short name → main dep at the newest stable.
            var hit = LibraryCatalog.bundled().lookup(id.strip()).orElse(null);
            if (hit != null) {
                String coord = hit.group() + ":" + hit.artifact();
                byScope.computeIfAbsent("main", _ -> new LinkedHashMap<>())
                        .putIfAbsent(hit.artifact(), new Pick(coord, null, "main"));
                continue;
            }
            // Free-form group:artifact or group:artifact:version
            String raw = id.strip();
            String[] parts = raw.split(":");
            if (parts.length == 2 || parts.length == 3) {
                String group = parts[0].strip();
                String artifact = parts[1].strip();
                String version = parts.length == 3 && !parts[2].isBlank() ? parts[2].strip() : null;
                if (!group.isEmpty() && !artifact.isEmpty()) {
                    byScope.computeIfAbsent("main", _ -> new LinkedHashMap<>())
                            .putIfAbsent(artifact, new Pick(group + ":" + artifact, version, "main"));
                }
            }
        }
        Map<String, List<Pick>> out = new LinkedHashMap<>();
        byScope.forEach((scope, entries) -> out.put(scope, List.copyOf(entries.values())));
        return out;
    }

    /** One lookup per coordinate per render: lombok's processor and provided entries carry the same number. */
    private static final class Lookup {
        private final ScaffoldVersions versions;
        private final Map<String, String> memo = new HashMap<>();

        Lookup(ScaffoldVersions versions) {
            this.versions = versions;
        }

        String stable(String coord) throws IOException {
            String hit = memo.get(coord);
            if (hit != null) return hit;
            int colon = coord.indexOf(':');
            String v = versions.newestStable(coord.substring(0, colon), coord.substring(colon + 1));
            if (v.isBlank()) {
                throw new IOException("no stable release found for " + coord);
            }
            memo.put(coord, v);
            return v;
        }
    }
}
