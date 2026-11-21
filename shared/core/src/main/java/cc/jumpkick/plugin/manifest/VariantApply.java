// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.UserConfigParseException;
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
import org.jspecify.annotations.Nullable;

/**
 * Folds selected {@code [variants]} overlays into a flat effective {@link JkBuild} (and plugin
 * configs). Precedence: custom dimensions in declaration order, then {@code build-type} last.
 * Dimensions without defaults must be selected, except on a guard run, where they take their first
 * declared value; undeclared dimension selections are ignored.
 *
 * <p>Signing sub-tables flatten to {@code signing.*}; {@code env:NAME} resolves client env then
 * process env. A {@code secret = true} key, top-level, in a group or in an entry, is resolved the
 * same way and moved off the values onto the plugin's own {@link PluginConfig#secrets}.
 */
public final class VariantApply {

    private VariantApply() {}

    /**
     * The selection a run builds: {@code raw}'s wire form, with unselected dimensions taking their
     * first value on a guard run ({@link Session#lanesOnly()}).
     */
    public static Selection selection(@Nullable String raw, Session session) {
        return Selection.parse(raw).withFirstUnselected(session.lanesOnly());
    }

    /**
     * Why {@code selection} cannot build {@code build}: an unselected mandatory dimension or a value
     * the module does not declare. Null when it applies. Reads the declaration alone, so a workspace
     * checks every module before any work starts.
     */
    public static @Nullable String selectionError(JkBuild build, Selection selection) {
        try {
            for (Variants.Dimension dimension : build.variants().custom()) chooseCustom(dimension, selection, false);
            chooseBuildType(build.variants(), selection);
            return null;
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (JkBuildParseException e) {
            return e.getMessage();
        }
    }

    /** As {@link #apply(JkBuild, Path, Selection, Map)} with defaults — non-build consumers. */
    public static JkBuild applyDefaults(JkBuild build, Path moduleDir) {
        return apply(build, moduleDir, Selection.DEFAULTS, Map.of());
    }

    public static JkBuild apply(
            JkBuild build, Path moduleDir, Selection selection, @Nullable Map<String, String> clientEnv) {
        return apply(build, moduleDir, selection, clientEnv, false);
    }

    /**
     * As {@link #apply} but an unselected mandatory dimension is SKIPPED, not an error — for
     * non-build consumers (exec plans, plugin commands) that must answer before/without a full
     * selection ({@code jk android licenses} runs pre-selection by design). Builds never use
     * this: silently building "no variant" is exactly what the mandatory check prevents.
     */
    public static JkBuild applyLenient(
            JkBuild build, Path moduleDir, Selection selection, @Nullable Map<String, String> clientEnv) {
        return apply(build, moduleDir, selection, clientEnv, true);
    }

    private static JkBuild apply(
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
            out = out.withDependencyScopes(merged);
        }

        // Plugin folds: overlay keys onto each plugin config, inject the selected names, then
        // flatten named group references (signing) with env: resolution / secret diversion.
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = out.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            out = out.withPluginConfig(effective(manifest, config, chosen, clientEnv, moduleDir));
        }
        return out;
    }

    /** One resolved dimension: its name, the selected value name, and that value's overlay. */
    private record Chosen(String dimension, String value, Variants.Value overlay) {}

    private static @Nullable Chosen chooseCustom(Variants.Dimension dimension, Selection selection, boolean lenient) {
        String name = selection.values().get(dimension.name());
        if (name == null) name = selection.values().get("*"); // bare --variant <value>
        if (name == null) name = dimension.defaultValue();
        if (name == null && selection.firstUnselected() && !dimension.values().isEmpty())
            name = dimension.values().keySet().iterator().next();
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
            Path moduleDir) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> secrets = new LinkedHashMap<>(config.secrets());
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

        // A top-level secret, as written or as a variant overlays it, leaves the values.
        divertSecrets(values, manifest.schema(), "", manifest.table() + ".", secrets, clientEnv, moduleDir);

        // An entry's secrets ([generate.api] secret-env) leave it under <entry>.<key>.
        Map<String, PluginDescriptor.SchemaKey> entrySchema = manifest.entrySchema() == null
                ? Map.of()
                : manifest.subSchemas().getOrDefault(manifest.entrySchema(), Map.of());
        if (entrySchema.values().stream().anyMatch(PluginDescriptor.SchemaKey::secret)
                && values.get(PluginConfig.ENTRIES) instanceof Map<?, ?> entries) {
            Map<String, Object> diverted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : entries.entrySet()) {
                String name = String.valueOf(e.getKey());
                if (!(e.getValue() instanceof Map<?, ?> raw)) {
                    diverted.put(name, e.getValue());
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                raw.forEach((k, v) -> entry.put(String.valueOf(k), v));
                divertSecrets(
                        entry,
                        entrySchema,
                        name + ".",
                        manifest.table() + "." + name + ".",
                        secrets,
                        clientEnv,
                        moduleDir);
                diverted.put(name, entry);
            }
            values.put(PluginConfig.ENTRIES, diverted);
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
            String where = manifest.table() + "." + group.table() + "." + name + ".";
            for (Map.Entry<String, Object> e : entry.entrySet()) {
                Object value = e.getValue();
                PluginDescriptor.SchemaKey schemaKey = subSchema.get(e.getKey());
                String flatKey = group.table() + "." + e.getKey();
                if (schemaKey != null && schemaKey.secret()) {
                    Object kept = divert(value, flatKey, where + e.getKey(), secrets, clientEnv, moduleDir);
                    if (kept != null) values.put(flatKey, kept);
                    continue;
                }
                if (value instanceof String s) value = resolveEnv(s, clientEnv, moduleDir, where + e.getKey());
                values.put(flatKey, value);
            }
        }
        return new PluginConfig(config.id(), values, secrets);
    }

    /**
     * Moves each {@code secret = true} key of {@code schema} that {@code values} sets onto {@code
     * secrets} under {@code prefix + key}, as {@link #divert} does.
     */
    private static void divertSecrets(
            Map<String, Object> values,
            Map<String, PluginDescriptor.SchemaKey> schema,
            String prefix,
            String where,
            Map<String, String> secrets,
            @Nullable Map<String, String> clientEnv,
            Path moduleDir) {
        for (PluginDescriptor.SchemaKey key : schema.values()) {
            Object value = key.secret() ? values.get(key.name()) : null;
            if (value == null) continue;
            Object kept = divert(value, prefix + key.name(), where + key.name(), secrets, clientEnv, moduleDir);
            if (kept == null) values.remove(key.name());
            else values.put(key.name(), kept);
        }
    }

    /**
     * Puts a secret's resolved value on {@code secrets} under {@code key} and returns what stays in
     * the values: nothing for a string; for a string-map, each variable's value goes under {@code
     * key.<variable>} and the map stays naming those keys, so a step knows which secret is which
     * variable while no value reaches the config.
     */
    private static @Nullable Object divert(
            Object value,
            String key,
            String where,
            Map<String, String> secrets,
            @Nullable Map<String, String> clientEnv,
            Path moduleDir) {
        if (!(value instanceof Map<?, ?> map)) {
            secrets.put(key, resolveEnv(String.valueOf(value), clientEnv, moduleDir, where));
            return null;
        }
        Map<String, String> keys = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String variable = String.valueOf(e.getKey());
            String secretKey = key + "." + variable;
            secrets.put(
                    secretKey, resolveEnv(String.valueOf(e.getValue()), clientEnv, moduleDir, where + "." + variable));
            keys.put(variable, secretKey);
        }
        return keys;
    }

    /**
     * {@code env:NAME} indirection via {@link BuildEnv} (request env + {@code .env} files).
     * An unresolvable reference fails loudly — a signing config must never silently sign with an
     * empty credential.
     */
    private static String resolveEnv(
            String raw, @Nullable Map<String, String> clientEnv, Path moduleDir, String where) {
        if (!raw.startsWith(EnvRead.PLUGIN_PREFIX)) return raw;
        String name = raw.substring(EnvRead.PLUGIN_PREFIX.length()).trim();
        // Prefer the client-shipped map (caller's shell) then BuildEnv (.env + session + process).
        String v = clientEnv == null ? null : clientEnv.get(name);
        if (v == null && moduleDir != null) {
            v = BuildEnv.forModule(moduleDir).apply(name);
        }
        if (v == null) v = BuildEnv.get(name);
        if (v == null) {
            throw new JkBuildParseException("[" + where + "] references env:" + name + " but " + name + " is not set");
        }
        return v;
    }

    /**
     * The entry {@link #envRefs} carries when a module says {@code [env] inherit = true}: the
     * workspace needs the caller's whole environment, not a list of names.
     */
    public static final String WHOLE_SHELL = "*";

    /**
     * Every environment variable name this manifest depends on, for the client to resolve and ship
     * with the request — the client sends the engine nothing else of the caller's shell beyond
     * what the engine reads for itself ({@code RequestEnvNames}). The names of {@link EnvRead#of};
     * {@link #WHOLE_SHELL} when the manifest inherits the caller's environment.
     */
    public static List<String> envRefs(JkBuild build) {
        return envRefs(List.of(build));
    }

    /**
     * The union over several manifests — what a workspace needs, not just its root. Over every
     * loaded module rather than the {@code -m} selection, so that narrowing a build cannot narrow
     * what the environment is allowed to say — an undeclared name resolves to nothing anyway.
     */
    public static List<String> envRefs(Collection<JkBuild> builds) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (JkBuild build : builds) {
            for (EnvRead read : EnvRead.of(build)) names.addAll(read.names());
        }
        return List.copyOf(names);
    }
}
