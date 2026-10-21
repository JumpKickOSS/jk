// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Reads JavaScript and TypeScript with oxc on the workspace's Node.js: one {@code node} run over
 * every changed script file. Without a Node.js and oxc install, or when the run fails, the
 * dependency-free scanner reads them instead.
 */
final class ScriptReader {

    /** The extensions oxc parses; Vue single-file components stay with the scanner. */
    private static final Set<String> EXTENSIONS = Set.of("js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts");

    private final @Nullable Path node;
    private final @Nullable Path oxc;

    ScriptReader(@Nullable Path node, @Nullable Path oxc) {
        this.node = node;
        this.oxc = oxc;
    }

    /** Whether oxc will read script files on this run. */
    boolean active() {
        return node != null
                && oxc != null
                && Files.isDirectory(oxc.resolve("node_modules").resolve("oxc-parser"));
    }

    /** The suffix that keeps an oxc fragment and a scanner fragment of the same bytes apart in the store. */
    String flavor(String rel) {
        return active() && handles(rel) ? "+oxc" : "";
    }

    static boolean handles(String rel) {
        int dot = rel.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(rel.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** The fragments oxc read for {@code rels}; a file it could not read is absent. */
    Map<String, Fragment> read(Path root, List<String> rels) {
        Map<String, Fragment> out = new HashMap<>();
        if (!active() || rels.isEmpty() || node == null || oxc == null) return out;
        Path work = null;
        try {
            work = Files.createTempDirectory("jk-graph-oxc");
            Path script = work.resolve("oxc-facts.cjs");
            try (InputStream in = ScriptReader.class.getResourceAsStream("oxc-facts.cjs")) {
                if (in == null) return out;
                Files.copy(in, script);
            }
            Path list = work.resolve("files.txt");
            Path facts = work.resolve("facts.jsonl");
            Files.writeString(list, String.join("\n", rels) + "\n", StandardCharsets.UTF_8);
            TaskExec.ToolRun run = new TaskExec.ToolRun(node)
                    .arg(script.toString())
                    .arg(oxc.toString())
                    .arg(root.toString())
                    .arg(list.toString())
                    .arg(facts.toString());
            int exit = run.stream(line -> Log.debug("graph oxc: " + line));
            if (exit != 0 || !Files.isRegularFile(facts)) return out;
            for (String line : Files.readAllLines(facts, StandardCharsets.UTF_8)) {
                try {
                    Fragment f = ScriptFacts.fragment(line);
                    if (f != null) out.put(f.nodes().getFirst().sourceFile(), f);
                } catch (RuntimeException malformed) {
                    Log.debug("graph: unreadable oxc facts line", malformed);
                }
            }
        } catch (IOException | RuntimeException failed) {
            Log.debug("graph: oxc run failed; the scanner reads script files", failed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (work != null) PathUtil.deleteRecursively(work);
        }
        return out;
    }
}
