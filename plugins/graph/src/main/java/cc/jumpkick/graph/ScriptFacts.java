// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.GraphIds;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.jsonl.MiniJson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One JavaScript/TypeScript file's facts, as {@code oxc-facts.cjs} reads them with oxc, turned into
 * graph nodes. Imports are already resolved (tsconfig paths, package exports), so a call to an
 * imported function links to the declaration in the file it came from; declarations carry a {@code
 * js:<path>#<name>} qualified name for that.
 */
final class ScriptFacts {

    private ScriptFacts() {}

    /** The qualified name a top-level script declaration is linked by. */
    static String qualified(String rel, String name) {
        int dot = rel.lastIndexOf('.');
        int slash = rel.lastIndexOf('/');
        String stem = dot > slash ? rel.substring(0, dot) : rel;
        return "js:" + stem + "#" + name;
    }

    /** The fragment for one facts line, or {@code null} when the script could not read the file. */
    static @Nullable Fragment fragment(String json) {
        Object f = MiniJson.parse(json);
        String rel = MiniJson.str(f, "file");
        if (rel == null || MiniJson.get(f, "failed") != null) return null;
        FragmentBuilder b = new FragmentBuilder(rel, "code", null);
        List<String> ids = declarations(b, rel, MiniJson.list(f, "decls"));
        Map<String, String> imported = imports(b, MiniJson.list(f, "imports"));
        for (Object c : MiniJson.list(f, "calls")) {
            String from = owner(b, ids, c);
            String callee = MiniJson.str(c, "callee");
            int line = num(c, "line");
            if (callee == null || MiniJson.get(c, "object") != null) continue;
            String target = imported.get(callee);
            if (target != null) {
                b.mention(from, Mention.Kind.CLASS, target, line, Relations.CALLS);
            } else if (b.has(GraphIds.declared(rel, callee))) {
                b.edge(from, GraphIds.declared(rel, callee), Relations.CALLS, Confidence.EXTRACTED, line, "call");
            }
        }
        for (Object u : MiniJson.list(f, "uses")) {
            String from = owner(b, ids, u);
            String name = String.valueOf(MiniJson.str(u, "name"));
            int line = num(u, "line");
            if ("file".equals(MiniJson.str(u, "kind"))) {
                b.mention(from, Mention.Kind.FILE, name, line, Relations.USES);
            } else if (imported.containsKey(name)) {
                b.mention(from, Mention.Kind.CLASS, imported.get(name), line, Relations.USES);
            } else if (b.has(GraphIds.declared(rel, name))) {
                b.edge(from, GraphIds.declared(rel, name), Relations.USES, Confidence.EXTRACTED, line);
            } else {
                b.mention(from, Mention.Kind.SYMBOL, name, line, Relations.USES);
            }
        }
        for (Object n : MiniJson.list(f, "notes")) {
            b.rationale(String.valueOf(MiniJson.str(n, "text")), num(n, "line"), b.fileId());
        }
        return b.build();
    }

    private static List<String> declarations(FragmentBuilder b, String rel, List<?> decls) {
        List<String> ids = new ArrayList<>();
        for (Object d : decls) {
            String name = String.valueOf(MiniJson.str(d, "name"));
            String kind = String.valueOf(MiniJson.str(d, "kind"));
            int line = num(d, "line");
            int parent = num(d, "parent");
            String id;
            if (parent >= 0 && parent < ids.size()) {
                String owner = ids.get(parent);
                id = b.node(GraphIds.member(owner, name), "." + name + "()", "code", line, Map.of());
                b.edge(owner, id, Relations.METHOD, Confidence.EXTRACTED, line);
            } else {
                Map<String, Object> extra = new HashMap<>();
                extra.put(Linker.QUALIFIED_NAME, qualified(rel, name));
                if (MiniJson.str(d, "selector") != null)
                    extra.put("attributes", Map.of("selector", MiniJson.str(d, "selector")));
                id = b.declared(name, kind.equals("function") ? name + "()" : name, "code", line, extra);
                b.edge(b.fileId(), id, Relations.CONTAINS, Confidence.EXTRACTED, line);
            }
            ids.add(id);
        }
        return ids;
    }

    /** Records each import's edge; returns local name → the qualified name it refers to. */
    private static Map<String, String> imports(FragmentBuilder b, List<?> imports) {
        Map<String, String> locals = new HashMap<>();
        for (Object i : imports) {
            String spec = String.valueOf(MiniJson.str(i, "spec"));
            String resolved = MiniJson.str(i, "resolved");
            int line = num(i, "line");
            if (resolved != null) {
                b.mention(b.fileId(), Mention.Kind.FILE, "/" + resolved, line, Relations.IMPORTS_FROM);
                for (Object n : MiniJson.list(i, "names")) {
                    String local = MiniJson.str(n, "local");
                    String name = MiniJson.str(n, "imported");
                    if (local != null && name != null && !name.equals("*") && !name.equals("default")) {
                        locals.put(local, qualified(resolved, name));
                    }
                }
            } else if (!spec.startsWith(".") && !spec.startsWith("/")) {
                String[] parts = spec.split("/");
                String pkg = spec.startsWith("@") && parts.length > 1 ? parts[0] + "/" + parts[1] : parts[0];
                String id = b.external(GraphIds.make("npm", pkg), pkg);
                b.edge(b.fileId(), id, Relations.IMPORTS, Confidence.EXTRACTED, line);
            }
        }
        return locals;
    }

    private static String owner(FragmentBuilder b, List<String> ids, Object fact) {
        int from = num(fact, "from");
        return from >= 0 && from < ids.size() ? ids.get(from) : b.fileId();
    }

    private static int num(@Nullable Object node, String key) {
        return MiniJson.get(node, key) instanceof Number n ? n.intValue() : -1;
    }
}
