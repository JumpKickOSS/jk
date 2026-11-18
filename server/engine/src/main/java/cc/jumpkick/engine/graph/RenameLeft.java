// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a rename left that no compile or test reads, as the verdict after its check says it: the
 * uses it listed and nobody applied (a template's bean path, a compose file's variable, a SQL column,
 * a variable in a script, a port in a Dockerfile) and the hazards nobody pinned. The graph worker
 * writes them after each rename and {@code also}, one row each: {@code id \t must|may \t what \t rel
 * \t line}, {@code what} being the use's reason as the answer gave it. An agent told the task was
 * done trusted it and left the compose file's variable stale; told what is left, it needs no grep.
 */
public final class RenameLeft {

    /** Places named per kind of use before the rest are counted. */
    static final int PLACES = 3;

    /** Kinds of use named before the rest are counted. */
    static final int KINDS = 6;

    private RenameLeft() {}

    /** One use left: its id, whether it must change, what it is, and where ({@code rel} empty for a hazard). */
    record Use(String id, boolean must, String what, String rel, int line) {}

    /** The rows the worker wrote for the workspace holding {@code dir}; none when there is no record. */
    static List<Use> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        List<Use> out = new ArrayList<>();
        for (String row : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] f = row.split("\t", -1);
            if (f.length != 5) continue;
            try {
                out.add(new Use(f[0], f[1].equals("must"), unesc(f[2]), unesc(f[3]), Integer.parseInt(f[4])));
            } catch (NumberFormatException skipped) {
                // A row this engine cannot read is not a use it can name.
            }
        }
        return out;
    }

    /**
     * The verdict's sentence for the uses the last rename in {@code dir}'s workspace left, or {@code
     * ""} when it left none (or wrote no record).
     */
    public static String after(String dir) {
        Path root = GraphRunner.rootOf(Path.of(dir));
        try {
            return verdict(read(RenameCheck.proof(root).resolveSibling("unchecked")));
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    /**
     * The sentence for {@code left}: not done while a use that must change is left; else the
     * judgment calls left as they were, by kind, with their ids, so the agent knows what no check
     * covered without searching.
     */
    static String verdict(List<Use> left) {
        List<Use> must = left.stream().filter(Use::must).toList();
        List<Use> may = left.stream().filter(u -> !u.must()).toList();
        StringBuilder s = new StringBuilder();
        if (!must.isEmpty()) {
            s.append("Not done: no compile or test reads these uses, which should change too: ")
                    .append(kinds(must))
                    .append(". Apply them with also=")
                    .append(ids(must))
                    .append(", or edit them.");
        }
        if (!may.isEmpty()) {
            // What a compile or test reads was checked, so the agent need not check it again; the rest
            // is what the graph found, which is not everything a search could (its recall is partial
            // for some kinds of link), so the sentence claims no more than that.
            String done = "Done where a compile or test reads it: no build, test or search is needed to check it. ";
            s.append(must.isEmpty() ? done : " ")
                    .append("Left as they were (the graph found these; no compile or test reads them): ")
                    .append(kinds(may))
                    .append(". also=<id> changes one the task wants changed.");
        }
        return s.toString();
    }

    /** {@code uses} by what they are: {@code SQL column at db/schema.sql:3 (c8); h1 hazard, not pinned}. */
    private static String kinds(List<Use> uses) {
        Map<String, List<Use>> byWhat = new LinkedHashMap<>();
        for (Use u : uses)
            byWhat.computeIfAbsent(u.what(), k -> new ArrayList<>()).add(u);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<Use>> e : byWhat.entrySet()) {
            if (out.size() == KINDS) {
                out.add("+" + (byWhat.size() - KINDS) + " more kinds (repeat the rename to list them)");
                break;
            }
            List<Use> all = e.getValue();
            List<String> places = new ArrayList<>();
            for (Use u : all) {
                if (u.rel().isEmpty()) continue;
                if (places.size() == PLACES) {
                    places.add("+" + (all.size() - PLACES) + " more");
                    break;
                }
                places.add(u.rel() + ":" + u.line());
            }
            out.add(
                    places.isEmpty()
                            ? ids(all) + " " + e.getKey() + ", not pinned"
                            : e.getKey() + " at " + String.join(", ", places) + " (" + ids(all) + ")");
        }
        return String.join("; ", out);
    }

    /** The ids as {@code also} takes them, runs of candidates as ranges: {@code c3-c5,c9,h1}. */
    static String ids(List<Use> uses) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < uses.size()) {
            String id = uses.get(i).id();
            int j = i;
            while (id.startsWith("c")
                    && j + 1 < uses.size()
                    && uses.get(j + 1).id().equals("c" + (number(uses.get(j).id()) + 1))) {
                j++;
            }
            if (j == i) out.add(id);
            else if (j == i + 1) out.add(id + "," + uses.get(j).id());
            else out.add(id + "-" + uses.get(j).id());
            i = j + 1;
        }
        return String.join(",", out);
    }

    private static int number(String id) {
        return Integer.parseInt(id.substring(1));
    }

    private static String unesc(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 == s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            out.append(
                    switch (n) {
                        case 't' -> '\t';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        default -> n;
                    });
        }
        return out.toString();
    }
}
