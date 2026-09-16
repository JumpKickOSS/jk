// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.Feature;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.SourcesMode;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.util.MinimalToml;
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
        renderJavac(sb, jkBuild.build().javac());
        renderProfiles(sb, jkBuild);
        renderFeatures(sb, jkBuild);
        renderRepositories(sb, jkBuild.repositories());
        renderDependencies(sb, jkBuild);
        return sb.toString();
    }

    /** {@code [build] extra-src} and {@code [test] extra-src} — the source roots beyond the layout's own. */
    private static void renderBuild(StringBuilder sb, JkBuild.Build build) {
        if (!build.extraSrc().isEmpty()) {
            sb.append("\n[build]\nextra-src = ").append(list(build.extraSrc())).append('\n');
        }
        if (!build.testExtraSrc().isEmpty()) {
            sb.append("\n[test]\nextra-src = ")
                    .append(list(build.testExtraSrc()))
                    .append('\n');
        }
    }

    /** {@code [javac]} — plugin names with their options, then verbatim args. */
    private static void renderJavac(StringBuilder sb, JavacConfig javac) {
        if (javac.isEmpty()) return;
        sb.append("\n[javac]\n");
        if (!javac.args().isEmpty())
            sb.append("args = ").append(list(javac.args())).append('\n');
        javac.plugins().forEach((name, options) -> {
            sb.append("\n[javac.plugins.").append(safeKey(name)).append("]\n");
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
     * keys in schema order, values formatted by type, entries equal to their schema default
     * omitted (and absent tri-state keys stay absent) — so the round trip through {@code jk
     * import} stays as minimal as the hand-written Boot renderer was. Zero framework knowledge
     * lives here.
     */
    private static void renderPluginTables(StringBuilder sb, JkBuild jkBuild) {
        for (var manifest : PluginTableRegistry.manifests()) {
            var config = jkBuild.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            sb.append("\n[").append(manifest.table()).append("]\n");
            for (var schemaKey : manifest.schema().values()) {
                Object value = config.values().get(schemaKey.name());
                if (value == null || value.equals(schemaKey.normalizedDefault())) continue;
                sb.append(schemaKey.name()).append(" = ");
                if (value instanceof String str) {
                    sb.append(quote(str));
                } else if (value instanceof List<?> list) {
                    sb.append('[');
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(quote(String.valueOf(list.get(i))));
                    }
                    sb.append(']');
                } else {
                    sb.append(value); // bool / int render bare
                }
                sb.append('\n');
            }
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
            sb.append(safeKey(r.name()))
                    .append(" = ")
                    .append(quote(r.url().toString()))
                    .append('\n');
        }
    }

    private static void renderDependencies(StringBuilder sb, JkBuild jkBuild) {
        Map<Scope, List<Dependency>> byScope = jkBuild.dependencies().byScope();
        if (byScope.isEmpty()) return;
        for (Scope scope : new Scope[] {
            Scope.PLATFORM,
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
            // Sort by short name for determinism. The dep `name` is the
            // user-facing manifest key; module ordering is no longer the
            // identifier.
            Map<String, Dependency> sorted = new TreeMap<>();
            for (Dependency d : deps) sorted.put(d.library(), d);

            sb.append('\n');
            sb.append('[').append(scope.tomlSection()).append("]\n");
            for (Dependency d : sorted.values()) {
                sb.append(renderEntry(d)).append('\n');
            }
        }
    }

    /** One dependency line: workspace flag, git table, or versioned table. */
    private static String renderEntry(Dependency d) {
        if (d.isWorkspace()) {
            // Shorthand only for the default main kind; kind=tests and optional need the table form.
            if (d.kind() == DependencyKind.MAIN && !d.optional()) {
                return safeKey(d.library()) + ".workspace = true";
            }
            StringBuilder ws = new StringBuilder(safeKey(d.library())).append(" = { workspace = true");
            if (d.kind() != DependencyKind.MAIN)
                ws.append(", kind = ").append(quote(d.kind().toml()));
            if (d.optional()) ws.append(", optional = true");
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
            if (d.isTestsKind()) {
                sb.append(", kind = ").append(quote(d.kind().toml()));
            }
        }
        if (d.optional()) sb.append(", optional = true");
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
     * TOML bare-key check — a name with only [A-Za-z0-9_-] can be emitted unquoted; anything else
     * gets wrapped in a quoted key.
     */
    private static String safeKey(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
                return quote(name);
            }
        }
        return name;
    }

    private static String quote(String s) {
        return MinimalToml.quote(s);
    }
}
