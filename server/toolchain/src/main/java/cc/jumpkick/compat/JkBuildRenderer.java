// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.Feature;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JavadocMode;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PinPolicy;
import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.SourcesMode;
import cc.jumpkick.model.UnmappedPolicy;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.util.MinimalToml;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Renders a {@link JkBuild} as name-as-key {@code jk.toml}: the project block, plugin tables,
 * application / native / manifest, the workspace, {@code [build]} / {@code [test]} source roots,
 * {@code [javac]}, profiles, features, repositories and the dependency tables. Dep keys within a
 * scope are alphabetized; a table whose every value is its default is not written.
 */
public final class JkBuildRenderer {

    private JkBuildRenderer() {}

    public static String render(JkBuild jkBuild) {
        Objects.requireNonNull(jkBuild, "jkBuild");
        StringBuilder sb = new StringBuilder();
        renderProject(sb, jkBuild.project());
        renderPluginTables(sb, jkBuild);
        renderApplication(sb, jkBuild.applicationOpt().orElse(null));
        renderNative(sb, jkBuild.nativeConfigOpt().orElse(null));
        renderManifest(sb, jkBuild.manifest());
        renderWorkspace(sb, jkBuild);
        renderBuild(sb, jkBuild.build());
        renderBuildInfo(sb, jkBuild.build().buildInfo());
        renderDokka(sb, jkBuild.build().dokka());
        renderResolve(sb, jkBuild.build());
        renderJavac(sb, jkBuild.build().javac());
        renderProfiles(sb, jkBuild);
        renderFeatures(sb, jkBuild);
        renderRepositories(sb, jkBuild.repositories());
        renderDependencies(sb, jkBuild);
        return sb.toString();
    }

    /**
     * {@code [build] extra-src}; {@code [test]} extra source roots, the baseline tag filters, and
     * the test JVM's flags and system properties.
     */
    private static void renderBuild(StringBuilder sb, JkBuild.Build build) {
        if (!build.extraSrc().isEmpty()) {
            sb.append("\n[build]\nextra-src = ").append(list(build.extraSrc())).append('\n');
        }
        if (build.testExtraSrc().isEmpty()
                && build.testIncludeTags().isEmpty()
                && build.testExcludeTags().isEmpty()
                && build.testJvm().isEmpty()) {
            return;
        }
        sb.append("\n[test]\n");
        if (!build.testExtraSrc().isEmpty())
            sb.append("extra-src = ").append(list(build.testExtraSrc())).append('\n');
        if (!build.testIncludeTags().isEmpty())
            sb.append("include-tags = ").append(list(build.testIncludeTags())).append('\n');
        if (!build.testExcludeTags().isEmpty())
            sb.append("exclude-tags = ").append(list(build.testExcludeTags())).append('\n');
        if (!build.testJvm().jvmArgs().isEmpty())
            sb.append("jvm-args = ").append(list(build.testJvm().jvmArgs())).append('\n');
        if (!build.testJvm().systemProperties().isEmpty()) {
            sb.append("system-properties = ")
                    .append(inlineTable(build.testJvm().systemProperties()))
                    .append('\n');
        }
    }

    /** {@code [build-info]} — the table itself is the declaration; only keys off their defaults are written. */
    private static void renderBuildInfo(StringBuilder sb, JkBuild.@Nullable BuildInfo info) {
        if (info == null) return;
        sb.append("\n[build-info]\n");
        if (!info.file().equals(JkBuild.BuildInfo.DEFAULT_FILE)) {
            sb.append("file = ").append(quote(info.file())).append('\n');
        }
        if (info.buildTime()) sb.append("time = \"build\"\n");
    }

    /** {@code [dokka]} — only when a key is off its default. */
    private static void renderDokka(StringBuilder sb, JkBuild.Dokka dokka) {
        if (dokka.isDefault()) return;
        sb.append("\n[dokka]\n");
        if (!dokka.version().equals(JkBuild.Dokka.DEFAULT.version())) {
            sb.append("version = ").append(quote(dokka.version().raw())).append('\n');
        }
        if (dokka.format() != JkBuild.Dokka.DEFAULT.format()) {
            sb.append("format = ").append(quote(dokka.format().wireName())).append('\n');
        }
    }

    /** {@code { key = "value", "dotted.key" = "value" }} in map order. */
    private static String inlineTable(Map<String, String> values) {
        StringBuilder sb = new StringBuilder("{ ");
        boolean first = true;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(safeKey(e.getKey())).append(" = ").append(quote(e.getValue()));
        }
        return sb.append(" }").toString();
    }

    /** {@code [resolve]} — only the policies that differ from their defaults. */
    private static void renderResolve(StringBuilder sb, JkBuild.Build build) {
        boolean platform = build.platformPolicy() != PlatformPolicy.ENFORCED;
        boolean unmapped = build.unmappedPolicy() != UnmappedPolicy.MEDIATE;
        boolean pins = build.pinPolicy() != PinPolicy.EXACT;
        if (!platform && !unmapped && !pins) return;
        sb.append("\n[resolve]\n");
        if (platform)
            sb.append("platform = \"").append(build.platformPolicy().wireName()).append("\"\n");
        if (unmapped)
            sb.append("unmapped = \"").append(build.unmappedPolicy().wireName()).append("\"\n");
        if (pins) sb.append("pins = \"").append(build.pinPolicy().wireName()).append("\"\n");
    }

    /**
     * {@code [javac]} — verbatim args, then {@code [javac.test]} when compile-test has a table of
     * its own (an empty one is spelled {@code plugins = {}}), then each plugin's options table.
     */
    private static void renderJavac(StringBuilder sb, JavacConfig javac) {
        JavacConfig test = javac.test();
        if (javac.isEmpty() && test == null) return;
        if (!javac.isEmpty()) {
            sb.append("\n[javac]\n");
            if (!javac.args().isEmpty())
                sb.append("args = ").append(list(javac.args())).append('\n');
        }
        if (test != null) {
            sb.append("\n[javac.test]\n");
            if (test.release() != null)
                sb.append("release = ").append(test.release()).append('\n');
            if (!test.args().isEmpty())
                sb.append("args = ").append(list(test.args())).append('\n');
            if (test.isEmpty() && test.release() == null) sb.append("plugins = {}\n");
            renderJavacPlugins(sb, "[javac.test.plugins.", test.plugins());
        }
        renderJavacPlugins(sb, "[javac.plugins.", javac.plugins());
    }

    private static void renderJavacPlugins(StringBuilder sb, String prefix, Map<String, List<String>> plugins) {
        plugins.forEach((name, options) -> {
            sb.append('\n').append(prefix).append(safeKey(name)).append("]\n");
            if (!options.isEmpty())
                sb.append("options = ").append(list(options)).append('\n');
        });
    }

    /** One {@code [profiles.<name>]} per profile: {@code javac}, {@code jvm-args}, tag filters when set. */
    private static void renderProfiles(StringBuilder sb, JkBuild jkBuild) {
        for (Profile p : jkBuild.profiles().byName().values()) {
            sb.append("\n[profiles.").append(safeKey(p.name())).append("]\n");
            if (p.inherits() != null)
                sb.append("inherits = ").append(quote(p.inherits())).append('\n');
            if (!p.javacArgs().isEmpty())
                sb.append("javac = ").append(list(p.javacArgs())).append('\n');
            if (!p.jvmArgs().isEmpty())
                sb.append("jvm-args = ").append(list(p.jvmArgs())).append('\n');
            if (p.includeTagsSet())
                sb.append("include-tags = ").append(list(p.includeTags())).append('\n');
            if (p.excludeTagsSet())
                sb.append("exclude-tags = ").append(list(p.excludeTags())).append('\n');
        }
    }

    /** {@code [features] default} when there is a default list, then one {@code [features.<name>]} per feature. */
    private static void renderFeatures(StringBuilder sb, JkBuild jkBuild) {
        var features = jkBuild.features();
        if (!features.defaults().isEmpty()) {
            sb.append("\n[features]\ndefault = ")
                    .append(list(features.defaults()))
                    .append('\n');
        }
        for (Feature f : features.byName().values()) {
            sb.append("\n[features.").append(safeKey(f.name())).append("]\n");
            if (!f.deps().isEmpty()) sb.append("deps = ").append(list(f.deps())).append('\n');
            if (!f.features().isEmpty())
                sb.append("features = ").append(list(f.features())).append('\n');
        }
    }

    /** A TOML array of quoted strings. */
    private static String list(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quote(values.get(i)));
        }
        return sb.append(']').toString();
    }

    /** {@code [manifest]} table — custom jar-manifest attributes, in insertion order. */
    private static void renderManifest(StringBuilder sb, Map<String, String> manifest) {
        if (manifest == null || manifest.isEmpty()) return;
        sb.append("\n[manifest]\n");
        for (Map.Entry<String, String> e : manifest.entrySet()) {
            sb.append(quote(e.getKey()))
                    .append(" = ")
                    .append(quote(e.getValue()))
                    .append('\n');
        }
    }

    private static void renderProject(StringBuilder sb, Project p) {
        sb.append("group    = ").append(quote(p.group())).append('\n');
        sb.append("name     = ").append(quote(p.name())).append('\n');
        sb.append("version  = ").append(quote(p.version())).append('\n');
        if (p.description() != null) {
            // Longer key than the rest of the block; emit unpadded.
            sb.append("description = ").append(quote(p.description())).append('\n');
        }
        if (p.jdk() != null) {
            sb.append("jdk      = ").append(quote(p.jdk())).append('\n');
        }
        if (p.sourcesMode() == SourcesMode.ALWAYS) {
            sb.append("sources  = \"always\"\n");
        } else if (p.sourcesMode() == SourcesMode.PUBLISH) {
            sb.append("sources  = true\n");
        }
        if (p.javadocMode() == JavadocMode.STRICT) {
            sb.append("javadoc  = \"strict\"\n");
        } else if (p.javadocMode() == JavadocMode.DISABLED) {
            sb.append("javadoc  = false\n");
        }
        if (p.isKotlin()) {
            sb.append("kotlin   = ")
                    .append(quote(versionLiteral(Objects.requireNonNull(p.kotlin()))))
                    .append('\n');
        } else if (p.java() > 0) {
            sb.append("java     = ").append(p.java()).append('\n');
        }
        if (p.isScala()) {
            sb.append("scala    = ")
                    .append(quote(versionLiteral(Objects.requireNonNull(p.scala()))))
                    .append('\n');
        }
        if (!p.m2integration() || !p.m2install()) {
            sb.append("\n[m2]\n");
            if (!p.m2integration()) sb.append("integration = false\n");
            if (!p.m2install()) sb.append("install = false\n");
        }
    }

    /**
     * Every plugin-owned table ({@code [spring-boot]}, …), rendered from its manifest schema:
     * keys in schema order, values formatted by type, values equal to their schema default
     * omitted (and absent tri-state keys stay absent) — so the round trip through {@code jk
     * import} stays as minimal as the hand-written Boot renderer was. The table's
     * {@code [<table>.<name>]} entries follow it, each against the manifest's entry schema. Zero
     * framework knowledge lives here.
     */
    private static void renderPluginTables(StringBuilder sb, JkBuild jkBuild) {
        for (PluginConfig config : jkBuild.pluginConfigs().values()) {
            PluginDescriptor manifest = PluginTableRegistry.byIdOrTable(config.id());
            String table = manifest != null ? manifest.table() : config.id();
            Map<String, Object> own = new LinkedHashMap<>(config.values());
            own.remove(PluginConfig.ENTRIES);
            Map<String, Map<String, Object>> entries = config.entries();
            // A table of entries alone has no bare header: one [<table>.<name>] sub-table per entry.
            if (!own.isEmpty() || entries.isEmpty()) {
                sb.append("\n[").append(table).append("]\n");
                // No manifest installed here: every value as the model carries it, so the table is
                // never dropped from the file it belongs in.
                renderPluginKeys(sb, manifest == null ? Map.of() : manifest.schema(), own);
            }
            Map<String, PluginDescriptor.SchemaKey> entrySchema = manifest == null || manifest.entrySchema() == null
                    ? Map.of()
                    : manifest.subSchemas().getOrDefault(manifest.entrySchema(), Map.of());
            for (Map.Entry<String, Map<String, Object>> entry : entries.entrySet()) {
                sb.append("\n[")
                        .append(table)
                        .append('.')
                        .append(safeKey(entry.getKey()))
                        .append("]\n");
                renderPluginKeys(sb, entrySchema, entry.getValue());
            }
        }
    }

    /** One table's keys: in schema order minus defaults when a schema is known, else every value as carried. */
    private static void renderPluginKeys(
            StringBuilder sb, Map<String, PluginDescriptor.SchemaKey> schema, Map<String, Object> values) {
        if (schema.isEmpty()) {
            for (Map.Entry<String, Object> e : values.entrySet()) {
                if (PluginConfig.ENTRIES.equals(e.getKey())) continue;
                sb.append(safeKey(e.getKey())).append(" = ");
                renderPluginValue(sb, e.getValue());
                sb.append('\n');
            }
            return;
        }
        for (var schemaKey : schema.values()) {
            Object value = values.get(schemaKey.name());
            if (value == null || value.equals(schemaKey.normalizedDefault())) continue;
            sb.append(schemaKey.name()).append(" = ");
            renderPluginValue(sb, value);
            sb.append('\n');
        }
    }

    /** A plugin table value by its runtime shape: string, string map, string list, else bare (bool / int). */
    private static void renderPluginValue(StringBuilder sb, Object value) {
        if (value instanceof String str) {
            sb.append(quote(str));
        } else if (value instanceof Map<?, ?> map) {
            Map<String, String> strings = new LinkedHashMap<>();
            map.forEach((k, v) -> strings.put(String.valueOf(k), String.valueOf(v)));
            sb.append(inlineTable(strings));
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(quote(String.valueOf(list.get(i))));
            }
            sb.append(']');
        } else {
            sb.append(value);
        }
    }

    /** {@code [application]} table — its presence alone marks the project as an application. */
    private static void renderApplication(StringBuilder sb, JkBuild.@Nullable Application app) {
        if (app == null) return;
        sb.append("\n[application]\n");
        if (app.main() != null)
            sb.append("main       = ").append(quote(app.main())).append('\n');
        if (app.assembly()) sb.append("assembly = true\n");
        if (app.minified()) sb.append("minified = true\n");
        if (app.nativeImage()) sb.append("native   = true\n");
        if (app.config() != null)
            sb.append("config   = ").append(quote(app.config())).append('\n');
    }

    /** {@code [native]} table — {@code enabled} defaults true when the table is present. */
    private static void renderNative(StringBuilder sb, JkBuild.@Nullable NativeConfig nc) {
        if (nc == null) return;
        sb.append("\n[native]\n");
        // Omit enabled when SUPPORTED (table presence == enabled true). Emit false / "always".
        if (nc.enabled() == JkBuild.NativeMode.ALWAYS) {
            sb.append("enabled    = \"always\"\n");
        } else if (nc.enabled() == JkBuild.NativeMode.DISABLED) {
            sb.append("enabled    = false\n");
        }
        if (nc.mainClass() != null)
            sb.append("main       = ").append(quote(nc.mainClass())).append('\n');
        if (nc.name() != null)
            sb.append("name       = ").append(quote(nc.name())).append('\n');
        if (!nc.args().isEmpty()) {
            sb.append("args       = [");
            for (int i = 0; i < nc.args().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(quote(nc.args().get(i)));
            }
            sb.append("]\n");
        }
        // graal defaults to "graalvm" at parse time when [native] is declared and the key is
        // omitted — elide exactly that default so a round-trip stays minimal. "native" is a
        // DISTINCT legal spec (parseGraalSpec: "graalvm-25", "25", or "native"); eliding it
        // silently re-parsed as "graalvm" and flipped the toolchain choice.
        if (nc.graal() != null && !nc.graal().equals("graalvm")) {
            sb.append("graal      = ").append(quote(nc.graal())).append('\n');
        }
        // Same rule as graal: the parser substitutes METADATA_REPOSITORY_DEFAULT for an omitted
        // key, so eliding exactly that selector keeps a round-trip minimal without losing a
        // deliberate pin — `=1.1.4` and `^1` both survive.
        VersionSelector metadata = nc.metadataRepository();
        if (metadata != null && !metadata.raw().equals(JkBuild.NativeConfig.METADATA_REPOSITORY_DEFAULT.raw())) {
            sb.append("metadata-repository = ").append(quote(metadata.raw())).append('\n');
        }
    }

    private static void renderWorkspace(StringBuilder sb, JkBuild jkBuild) {
        if (!jkBuild.isWorkspaceRoot()) return;
        sb.append('\n');
        sb.append("[workspace]\n");
        sb.append("modules = [");
        List<String> modules = Objects.requireNonNull(jkBuild.workspace()).modules();
        for (int i = 0; i < modules.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quote(modules.get(i)));
        }
        sb.append("]\n");
    }

    private static void renderRepositories(StringBuilder sb, List<RepositorySpec> repos) {
        if (repos.isEmpty()) return;
        sb.append('\n');
        sb.append("[repositories]\n");
        for (RepositorySpec r : repos) {
            sb.append(safeKey(r.name())).append(" = ");
            if (r.releases() && r.snapshots()) {
                sb.append(quote(r.url().toString()));
            } else {
                // A policy other than Maven's default needs the table form.
                sb.append("{ url = ").append(quote(r.url().toString()));
                if (!r.releases()) sb.append(", releases = false");
                if (!r.snapshots()) sb.append(", snapshots = false");
                sb.append(" }");
            }
            sb.append('\n');
        }
    }

    private static void renderDependencies(StringBuilder sb, JkBuild jkBuild) {
        Map<Scope, List<Dependency>> byScope = jkBuild.dependencies().byScope();
        if (byScope.isEmpty()) return;
        for (Scope scope : new Scope[] {
            Scope.PLATFORM,
            Scope.MANAGED,
            Scope.MAIN,
            Scope.RUNTIME,
            Scope.DEV,
            Scope.TEST_DEV,
            Scope.PROVIDED,
            Scope.TEST,
            Scope.PROCESSOR
        }) {
            List<Dependency> deps = byScope.get(scope);
            if (deps == null || deps.isEmpty()) continue;
            sb.append('\n');
            sb.append('[').append(scope.tomlSection()).append("]\n");
            for (Dependency d : ordered(scope, deps)) {
                sb.append(renderEntry(d)).append('\n');
            }
        }
    }

    /**
     * The rows of one scope table. {@code [platform-dependencies]} is ordered — the first BOM
     * that manages a module wins — so it keeps declaration order; every other table sorts by the
     * manifest key.
     */
    private static Collection<Dependency> ordered(Scope scope, List<Dependency> deps) {
        if (scope == Scope.PLATFORM) return deps;
        Map<String, Dependency> sorted = new TreeMap<>();
        for (Dependency d : deps) sorted.put(d.library(), d);
        return sorted.values();
    }

    /** One dependency line: workspace flag, git table, or versioned table (with its classifier when set). */
    private static String renderEntry(Dependency d) {
        if (d.isWorkspace()) {
            // Shorthand only for the default main kind; a group, kind=tests and optional need the table form.
            String group = d.workspaceGroup();
            if (group == null
                    && d.kind() == DependencyKind.MAIN
                    && !d.optional()
                    && d.exclusions().isEmpty()) {
                return safeKey(d.library()) + ".workspace = true";
            }
            StringBuilder ws = new StringBuilder(safeKey(d.library())).append(" = { workspace = true");
            if (group != null) ws.append(", group = ").append(quote(group));
            if (d.kind() != DependencyKind.MAIN)
                ws.append(", kind = ").append(quote(d.kind().toml()));
            if (d.optional()) ws.append(", optional = true");
            if (!d.exclusions().isEmpty()) ws.append(", exclude = ").append(list(d.exclusions()));
            return ws.append(" }").toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(safeKey(d.library())).append(" = { ");
        if (d.isGit()) {
            // Pure discovery: JkBuildParser rejects `group`/`name` alongside `git` — the
            // coordinate and version always come from the cloned repo's own jk.toml.
            GitSource s = Objects.requireNonNull(d.gitSource());
            sb.append("git = ").append(quote(s.originalUrl()));
            switch (s.ref()) {
                case GitRefSpec.Tag t -> sb.append(", tag = ").append(quote(t.name()));
                case GitRefSpec.Branch b -> sb.append(", branch = ").append(quote(b.name()));
                case GitRefSpec.Rev r -> sb.append(", rev = ").append(quote(r.sha()));
            }
            if (s.path() != null) sb.append(", path = ").append(quote(s.path()));
            if (!s.submodules()) sb.append(", submodules = false");
            if (s.verifySignature()) sb.append(", verify-signed = true");
        } else {
            sb.append("group = ").append(quote(d.group()));
            if (!d.name().equals(d.library())) {
                sb.append(", name = ").append(quote(d.name()));
            }
            // Platform-managed (versionless — a BOM pins it): no version clause; the
            // parser re-derives the platform-managed marker from its absence.
            if (!d.isPlatformManaged()) {
                sb.append(", version = ").append(quote(versionLiteral(d.version())));
            }
            if (d.classifier() != null) {
                sb.append(", classifier = ").append(quote(d.classifier()));
            }
            if (d.isTestsKind()) {
                sb.append(", kind = ").append(quote(d.kind().toml()));
            }
        }
        if (d.optional()) sb.append(", optional = true");
        if (!d.exclusions().isEmpty()) sb.append(", exclude = ").append(list(d.exclusions()));
        sb.append(" }");
        return sb.toString();
    }

    /**
     * Convert a {@link VersionSelector} into the literal that goes inside {@code version = "..."}.
     * An exact selector is the bare version; every floating selector carries its decoration.
     */
    private static String versionLiteral(VersionSelector v) {
        return switch (v) {
            case VersionSelector.Exact e -> e.version();
            case VersionSelector.Caret c -> "^" + c.version();
            case VersionSelector.Tilde t -> "~" + t.version();
            case VersionSelector.Range r -> r.raw();
            case VersionSelector.Latest l -> "latest";
            case VersionSelector.Snapshot sn -> "snapshot";
        };
    }

    /**
     * A TOML key: bare when every character is one of {@code A-Za-z0-9_-} (the bare-key alphabet
     * of the TOML spec; a letter outside ASCII is not in it), quoted otherwise.
     */
    private static String safeKey(String name) {
        if (name.isEmpty()) return quote(name);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean bare =
                    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!bare) return quote(name);
        }
        return name;
    }

    private static String quote(String s) {
        return MinimalToml.quote(s);
    }
}
