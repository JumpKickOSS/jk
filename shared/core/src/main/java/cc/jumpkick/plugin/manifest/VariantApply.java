// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.Interpolation;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.TestEnvValues;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.Variants;
import cc.jumpkick.model.Variants.Selection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Folds selected {@code [variants]} overlays into a flat effective {@link JkBuild} (and plugin
 * configs). Precedence: custom dimensions in declaration order, then {@code build-type} last.
 * Dimensions without defaults must be selected; undeclared dimension selections are ignored.
 *
 * <p>Signing sub-tables flatten to {@code signing.*}; {@code env:NAME} resolves client env then
 * process env. {@code secret = true} keys go only on {@link Applied#secrets} (digests in action keys).
 */
public final class VariantApply {

    private VariantApply() {}

    /** The variant-applied build plus the resolved secret values (package-spec side channel). */
    public record Applied(JkBuild build, Map<String, String> secrets) {}

    /** As {@link #apply(JkBuild, Path, Selection, Map)} with defaults — non-build consumers. */
    public static JkBuild applyDefaults(JkBuild build, Path moduleDir) {
        return apply(build, moduleDir, Selection.DEFAULTS, Map.of()).build();
    }

    public static Applied apply(
            JkBuild build, Path moduleDir, Selection selection, @Nullable Map<String, String> clientEnv) {
        return apply(build, moduleDir, selection, clientEnv, false);
    }

    /**
     * As {@link #apply} but an unselected mandatory dimension is SKIPPED, not an error — for
     * non-build consumers (exec plans, plugin commands) that must answer before/without a full
     * selection ({@code jk android licenses} runs pre-selection by design). Builds never use
     * this: silently building "no variant" is exactly what the mandatory check prevents.
     */
    public static Applied applyLenient(
            JkBuild build, Path moduleDir, Selection selection, @Nullable Map<String, String> clientEnv) {
        return apply(build, moduleDir, selection, clientEnv, true);
    }

    private static Applied apply(
            JkBuild build,
            Path moduleDir,
            Selection selection,
            @Nullable Map<String, String> clientEnv,
            boolean lenient) {
        Variants decl = build.variants();

        // Resolve the selection: (dimension, value name, overlay) per declared dimension — custom
        // dimensions in declaration order, build-type last (AGP's precedence, now core's).
        List<Chosen> chosen = new ArrayList<>();
        for (Variants.Dimension dimension : decl.custom()) {
            Chosen c = chooseCustom(dimension, selection, lenient);
            if (c != null) chosen.add(c);
        }
        chosen.add(chooseBuildType(decl, selection));

        // Core folds: extra-src and per-scope dependency overlays.
        JkBuild out = build;
        List<String> extraSrc = new ArrayList<>();
        EnumMap<Scope, List<Dependency>> extraDeps = new EnumMap<>(Scope.class);
        for (Chosen c : chosen) {
            extraSrc.addAll(c.overlay().extraSrc());
            c.overlay().dependencies().forEach((scope, deps) -> extraDeps
                    .computeIfAbsent(scope, s -> new ArrayList<>())
                    .addAll(deps));
        }
        if (!extraSrc.isEmpty()) out = out.withBuild(out.build().withExtraSrc(extraSrc));
        if (!extraDeps.isEmpty()) {
            Map<Scope, List<Dependency>> merged = new EnumMap<>(Scope.class);
            out.dependencies().byScope().forEach((scope, deps) -> merged.put(scope, new ArrayList<>(deps)));
            extraDeps.forEach((scope, deps) ->
                    merged.computeIfAbsent(scope, s -> new ArrayList<>()).addAll(deps));
            out = out.withDependencies(new JkBuild.Dependencies(merged));
        }

        // Plugin folds: overlay keys onto each plugin config, inject the selected names, then
        // flatten named group references (signing) with env: resolution / secret diversion.
        Map<String, String> secrets = new LinkedHashMap<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = out.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            out = out.withPluginConfig(effective(manifest, config, chosen, clientEnv, moduleDir, secrets));
        }
        return new Applied(out, secrets);
    }

    /** One resolved dimension: its name, the selected value name, and that value's overlay. */
    private record Chosen(String dimension, String value, Variants.Value overlay) {}

    private static @Nullable Chosen chooseCustom(Variants.Dimension dimension, Selection selection, boolean lenient) {
        String name = selection.values().get(dimension.name());
        if (name == null) name = selection.values().get("*"); // bare --variant <value>
        if (name == null) name = dimension.defaultValue();
        if (name == null) {
            if (lenient) return null;
            throw new JkBuildParseException("[variants." + dimension.name() + "] declares values "
                    + dimension.values().keySet() + " — select one with --variant "
                    + dimension.name() + "=<value>");
        }
        Variants.Value overlay = dimension.values().get(name);
        if (overlay == null) {
            throw new JkBuildParseException("no value `" + name + "` in [variants." + dimension.name() + "] (declared: "
                    + dimension.values().keySet() + ")");
        }
        return new Chosen(dimension.name(), name, overlay);
    }

    private static Chosen chooseBuildType(Variants decl, Selection selection) {
        Variants.Dimension dimension = decl.dimension(Variants.BUILD_TYPE)
                .orElseGet(() -> new Variants.Dimension(Variants.BUILD_TYPE, null, Map.of()));
        String name = selection.buildType() != null ? selection.buildType() : dimension.defaultValue();
        if (name == null) name = "debug";
        Variants.Value overlay = dimension.values().get(name);
        if (overlay == null) {
            if (!Variants.BUILT_IN_BUILD_TYPES.contains(name)) {
                throw new JkBuildParseException("no build type `" + name + "` in [variants.build-type]"
                        + " (declared: " + dimension.values().keySet()
                        + ", built-in: " + Variants.BUILT_IN_BUILD_TYPES + ")");
            }
            overlay = Variants.Value.EMPTY;
        }
        return new Chosen(Variants.BUILD_TYPE, name, overlay);
    }

    private static PluginConfig effective(
            PluginDescriptor manifest,
            PluginConfig config,
            List<Chosen> chosen,
            @Nullable Map<String, String> clientEnv,
            Path moduleDir,
            Map<String, String> secrets) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : config.values().entrySet()) {
            // Group DEFINITIONS never ride flat; a same-named string REFERENCE does.
            if (manifest.subTables().containsKey(e.getKey()) && e.getValue() instanceof Map) continue;
            values.put(e.getKey(), e.getValue());
        }

        for (Chosen c : chosen) {
            Map<String, Object> overlay = c.overlay().pluginOverlays().get(manifest.table());
            if (overlay != null) values.putAll(overlay);
            values.put(
                    Variants.BUILD_TYPE.equals(c.dimension()) ? Variants.BUILD_TYPE : "variant." + c.dimension(),
                    c.value());
        }

        // Named-group references (signing = "release" → [android.signing.release] flattens to
        // signing.<key>, secrets diverted to the side channel).
        for (PluginDescriptor.SubTable group : manifest.subTables().values()) {
            Object ref = values.get(group.table());
            if (!(ref instanceof String name) || name.isBlank()) continue;
            Map<String, Object> entry = config.group(group.table()).get(name);
            if (entry == null) {
                throw new JkBuildParseException("[" + manifest.table() + "] references " + group.table() + " = \""
                        + name + "\" but declares no [" + manifest.table() + "." + group.table() + "." + name + "]");
            }
            Map<String, PluginDescriptor.SchemaKey> subSchema =
                    manifest.subSchemas().getOrDefault(group.schema(), Map.of());
            for (Map.Entry<String, Object> e : entry.entrySet()) {
                Object value = e.getValue();
                if (value instanceof String s) {
                    value = resolveEnv(
                            s,
                            clientEnv,
                            moduleDir,
                            manifest.table() + "." + group.table() + "." + name + "." + e.getKey());
                }
                PluginDescriptor.SchemaKey schemaKey = subSchema.get(e.getKey());
                String flatKey = group.table() + "." + e.getKey();
                if (schemaKey != null && schemaKey.secret()) {
                    secrets.put(flatKey, String.valueOf(value));
                } else {
                    values.put(flatKey, value);
                }
            }
        }
        return new PluginConfig(config.id(), values);
    }

    /**
     * {@code env:NAME} indirection via {@link BuildEnv} (request env + {@code .env} files).
     * An unresolvable reference fails loudly — a signing config must never silently sign with an
     * empty credential. Values that came from {@code .env} land on the secrets side channel when
     * the schema marks them secret, and are redacted/hashed by.
     */
    private static String resolveEnv(
            String raw, @Nullable Map<String, String> clientEnv, Path moduleDir, String where) {
        if (!raw.startsWith("env:")) return raw;
        String name = raw.substring("env:".length()).trim();
        // Prefer the client-shipped map (caller's shell) then BuildEnv (.env + session + process).
        String v = clientEnv == null ? null : clientEnv.get(name);
        if (v == null && moduleDir != null) {
            v = BuildEnv.forModule(moduleDir).apply(name);
        }
        if (v == null) v = System.getenv(name);
        if (v == null) {
            throw new JkBuildParseException("[" + where + "] references env:" + name + " but " + name + " is not set");
        }
        return v;
    }

    /**
     * Every environment variable name this manifest depends on, for the client to resolve and ship
     * with the request.
     *
     * <p>Two vocabularies, one list: {@code env:NAME} in a plugin config (signing credentials), and
     * {@code [test] env} — both the bare names it forwards and the {@code ${NAME}} references in the
     * values it sets. The second half was the gap: {@code [test] env} resolved through
     * {@code BuildEnv}, which falls back to the engine's own environment, and the engine is a daemon
     * started by some earlier shell. So {@code JK_WEB_JS_SKIP=1 jk build} did nothing, while the
     * same variable exported before the daemon started worked — and kept working for every later
     * build in that daemon, which is the worse of the two failures.
     */
    public static List<String> envRefs(JkBuild build) {
        return envRefs(List.of(build));
    }

    /**
     * The union over several manifests — what a workspace needs, not just its root.
     *
     * <p>A workspace root's own manifest declares almost nothing; the module that wants a variable
     * is one of its members. Resolving only the root is why {@code JK_WEB_JS_SKIP=1 jk build} still
     * did nothing after {@code [test] env} learned to declare it: the name was in
     * {@code clients/web/jk.toml}, and the client was reading the root. The union is over every
     * loaded module rather than the {@code -m} selection, so that narrowing a build cannot narrow
     * what the environment is allowed to say — an undeclared name resolves to nothing anyway.
     */
    public static List<String> envRefs(Collection<JkBuild> builds) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (JkBuild build : builds) collectEnvRefs(build, names);
        return List.copyOf(names);
    }

    private static void collectEnvRefs(JkBuild build, LinkedHashSet<String> names) {
        for (PluginConfig config : build.pluginConfigs().values()) {
            collectEnvRefs(config.values(), names);
        }
        for (JkBuild.EnvDecl decl : build.build().testEnvDecls()) {
            switch (decl) {
                case JkBuild.EnvDecl.Forward forward -> names.add(forward.name());
                case JkBuild.EnvDecl.Set set -> collectReferences(set.value(), names);
            }
        }
    }

    /** The {@code ${NAME}} references in one value, minus jk's own layout tokens. */
    private static void collectReferences(String value, Set<String> names) {
        for (String name : Interpolation.references(value)) {
            if (!TestEnvValues.isPathToken(name)) names.add(name);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectEnvRefs(Map<String, Object> values, Set<String> names) {
        for (Object v : values.values()) {
            if (v instanceof String s && s.startsWith("env:")) {
                names.add(s.substring("env:".length()).trim());
            } else if (v instanceof Map<?, ?> m) {
                collectEnvRefs((Map<String, Object>) m, names);
            }
        }
    }
}
