// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * The guard rules whose reach covers one file, for {@code jk graph rules}: the rule's module scope
 * holds the file's module and no {@code allow} entry names the file or a directory above it. Rules
 * about the build rather than its sources (dependencies, toolchain, outputs, commits, parity) are
 * left out. The graph worker narrows further by source set, {@code files} globs and languages.
 */
final class GraphRules {

    private GraphRules() {}

    private static final Set<Kind> NOT_ABOUT_SOURCES =
            Set.of(Kind.DEPEND, Kind.TOOLCHAIN, Kind.OUTPUT, Kind.COMMIT, Kind.PARITY);

    /** One JSON object per rule: {@code id, kind, why, instead, set, files, languages, into}. */
    static List<String> rows(Path root, String file, List<String> modulePrefixes) {
        if (!Files.isRegularFile(root.resolve("jk-guards.toml"))) return List.of();
        LoadResult load;
        try {
            load = GuardRules.load(root, JkBuildParser.guardsConfig(ManifestPaths.manifestIn(root)), JkDirs.store());
        } catch (RuntimeException unloadable) {
            Log.debug("graph: guard rules unreadable at " + root, unloadable);
            return List.of();
        }
        String module = module(file, modulePrefixes);
        List<String> out = new ArrayList<>();
        for (String id : load.rules().ids()) {
            Rule r = load.rules().rule(id).orElse(null);
            if (r == null || NOT_ABOUT_SOURCES.contains(r.kind()) || !r.applies(module) || allowed(r, file)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.id());
            row.put("kind", r.kind().id());
            row.put("why", r.why());
            if (r.instead() != null) row.put("instead", r.instead());
            row.put("set", r.sourceSet());
            row.put("files", strings(r.table(), "files"));
            row.put("languages", strings(r.table(), "languages"));
            String into = r.table().getString("into");
            if (into != null) row.put("into", into);
            out.add(MiniJson.write(row));
        }
        return out;
    }

    /** The module holding {@code file}, as guard scopes spell it: its directory, {@code ""} for the root. */
    static String module(String file, List<String> modulePrefixes) {
        String best = "";
        for (String p : modulePrefixes) {
            if (file.startsWith(p) && p.length() > best.length()) best = p;
        }
        return best.endsWith("/") ? best.substring(0, best.length() - 1) : best;
    }

    private static boolean allowed(Rule r, String file) {
        for (Allow a : r.allow()) {
            String in = a.in();
            if (in.equals(file) || file.startsWith(in.endsWith("/") ? in : in + "/") || Rule.globMatches(in, file)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> strings(TomlTable table, String key) {
        TomlArray a = table.isArray(key) ? table.getArray(key) : null;
        List<String> out = new ArrayList<>();
        if (a == null) {
            @Nullable String one = table.isString(key) ? table.getString(key) : null;
            if (one != null) out.add(one);
            return out;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) instanceof String s) out.add(s);
        }
        return out;
    }
}
