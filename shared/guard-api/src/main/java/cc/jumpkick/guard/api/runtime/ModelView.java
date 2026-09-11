// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import cc.jumpkick.guard.api.DepScope;
import cc.jumpkick.guard.api.Dependency;
import cc.jumpkick.guard.api.Lock;
import cc.jumpkick.guard.api.Model;
import cc.jumpkick.guard.api.Tiers;
import cc.jumpkick.guard.api.Toolchain;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * {@link Model} from the engine's snapshot: {@code {"modules":[..], "deps":{module:{table:[{coordinate,
 * version,workspace}]}}, "lock":[{coordinate,version,repository,scopes}], "tiers":{"tiers":[{name,
 * include,exclude}]}, "toolchain":{"java":{module:release}, "kotlin":.., "repositories":[..]}}}.
 */
public final class ModelView implements Model {

    private final List<String> modules;
    private final Map<String, Map<DepScope, List<Dependency>>> deps;
    private final Lock lock;
    private final TierTable tiers;
    private final Toolchain toolchain;

    private ModelView(
            List<String> modules,
            Map<String, Map<DepScope, List<Dependency>>> deps,
            Lock lock,
            TierTable tiers,
            Toolchain toolchain) {
        this.modules = List.copyOf(modules);
        this.deps = deps;
        this.lock = lock;
        this.tiers = tiers;
        this.toolchain = toolchain;
    }

    public static ModelView empty() {
        return new ModelView(
                List.of(),
                Map.of(),
                new Lock(List.of()),
                new TierTable(List.of()),
                new Toolchain(Map.of(), null, List.of()));
    }

    public static ModelView read(Path json) throws IOException {
        return parse(Files.readString(json));
    }

    @SuppressWarnings("unchecked")
    public static ModelView parse(String json) {
        Object root = MiniJson.parse(json);
        List<String> modules = new ArrayList<>();
        for (Object m : list(MiniJson.get(root, "modules"))) modules.add(String.valueOf(m));
        Map<String, Map<DepScope, List<Dependency>>> deps = new LinkedHashMap<>();
        Object depsNode = MiniJson.get(root, "deps");
        if (depsNode instanceof Map<?, ?> byModule) {
            for (var e : byModule.entrySet()) {
                Map<DepScope, List<Dependency>> byScope = new LinkedHashMap<>();
                if (e.getValue() instanceof Map<?, ?> tables) {
                    for (var t : tables.entrySet()) {
                        DepScope scope = scope(String.valueOf(t.getKey()));
                        if (scope == null) continue;
                        List<Dependency> ds = new ArrayList<>();
                        for (Object d : list(t.getValue())) {
                            ds.add(new Dependency(
                                    text(d, "coordinate"),
                                    text(d, "version"),
                                    scope,
                                    Boolean.TRUE.equals(MiniJson.get(d, "workspace"))));
                        }
                        byScope.put(scope, List.copyOf(ds));
                    }
                }
                deps.put(String.valueOf(e.getKey()), byScope);
            }
        }
        List<Lock.Artifact> artifacts = new ArrayList<>();
        for (Object a : list(MiniJson.get(root, "lock"))) {
            List<String> scopes = new ArrayList<>();
            for (Object s : list(MiniJson.get(a, "scopes"))) scopes.add(String.valueOf(s));
            artifacts.add(new Lock.Artifact(text(a, "coordinate"), text(a, "version"), text(a, "repository"), scopes));
        }
        List<TierTable.Tier> tiers = new ArrayList<>();
        Object tiersNode = MiniJson.get(root, "tiers");
        for (Object t : list(tiersNode == null ? null : MiniJson.get(tiersNode, "tiers"))) {
            tiers.add(new TierTable.Tier(
                    text(t, "name"), strings(MiniJson.get(t, "include")), strings(MiniJson.get(t, "exclude"))));
        }
        Map<String, Integer> java = new LinkedHashMap<>();
        String kotlin = null;
        List<String> repositories = new ArrayList<>();
        Object tc = MiniJson.get(root, "toolchain");
        if (tc != null) {
            Object j = MiniJson.get(tc, "java");
            if (j instanceof Map<?, ?> jm)
                for (var e : jm.entrySet()) java.put(String.valueOf(e.getKey()), ((Number) e.getValue()).intValue());
            Object k = MiniJson.get(tc, "kotlin");
            kotlin = k == null ? null : String.valueOf(k);
            for (Object r : list(MiniJson.get(tc, "repositories"))) repositories.add(String.valueOf(r));
        }
        return new ModelView(
                modules, deps, new Lock(artifacts), new TierTable(tiers), new Toolchain(java, kotlin, repositories));
    }

    @Override
    public List<String> modules() {
        return modules;
    }

    @Override
    public List<Dependency> deps(String module, DepScope scope) {
        return deps.getOrDefault(module, Map.of()).getOrDefault(scope, List.of());
    }

    @Override
    public Lock lock() {
        return lock;
    }

    @Override
    public Tiers tiers() {
        return tiers;
    }

    @Override
    public Toolchain toolchain() {
        return toolchain;
    }

    private static List<?> list(@Nullable Object node) {
        return node instanceof List<?> l ? l : List.of();
    }

    /** {@link MiniJson#str} with the snapshot writer's own default: an absent string field is {@code ""}. */
    private static String text(@Nullable Object node, String key) {
        String s = MiniJson.str(node, key);
        return s == null ? "" : s;
    }

    private static Set<String> strings(@Nullable Object node) {
        Set<String> out = new LinkedHashSet<>();
        for (Object o : list(node)) out.add(String.valueOf(o));
        return out;
    }

    private static @Nullable DepScope scope(String table) {
        for (DepScope s : DepScope.values())
            if (s.table().equals(table) || s.name().equalsIgnoreCase(table)) return s;
        return null;
    }

    /** The tier table with JUnit's own tag semantics. */
    public record TierTable(List<Tier> tiers) implements Tiers {

        public record Tier(String name, Set<String> include, Set<String> exclude) {
            boolean runs(Set<String> tags) {
                boolean included = include.isEmpty();
                for (String t : tags) {
                    if (include.contains(t)) included = true;
                    if (exclude.contains(t)) return false;
                }
                return included;
            }
        }

        @Override
        public Set<String> tagVocabulary() {
            Set<String> out = new TreeSet<>();
            for (Tier t : tiers) {
                out.addAll(t.include());
                out.addAll(t.exclude());
            }
            return out;
        }

        @Override
        public List<String> running(Set<String> tags) {
            List<String> out = new ArrayList<>();
            for (Tier t : tiers) if (t.runs(tags)) out.add(t.name());
            return out;
        }
    }
}
