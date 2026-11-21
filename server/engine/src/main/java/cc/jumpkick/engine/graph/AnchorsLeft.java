// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import cc.jumpkick.config.GraphConfig;
import cc.jumpkick.engine.api.BuildHistoryKinds;
import cc.jumpkick.layout.AnchorWatch;
import cc.jumpkick.layout.GraphLayout;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What a hand edit left under an old name, as the verdict of the build after it says it: the places
 * a refresh of the graph found still carrying a name (a SQL column, a config key, an environment
 * variable, a route) that an edited file no longer uses. The graph worker keeps the record, one row
 * per place: {@code must|may \t place \t kind \t old \t now \t rel \t line \t written \t edited}. A
 * verdict names the places that must change in at most {@link #CAP} files, then how many more and the call
 * that lists them; it says nothing when none is left, and nothing of the judgment calls (prose) unless
 * {@code [graph] anchor-tail} asks for a count ({@code count} and {@code kinds}, experimental). This is the
 * default ({@code silent}) in a workspace whose root has a {@code jk.toml}; {@code anchor-tail = "off"} turns it
 * off, and then a verdict neither waits nor reads the record. Where nothing an anchor hangs on changed since the
 * last refresh ({@link AnchorWatch}), a verdict waits for nothing: a project with no anchors pays a stat or two.
 * Only MCP verdicts carry the lines; the CLI's output never does.
 */
public final class AnchorsLeft {

    /** Files named before the rest of the places are counted. */
    static final int CAP = 5;

    /** How long a verdict waits for the graph refresh its build started. */
    static final long REFRESH_WAIT_MS = 10_000;

    /**
     * Verdicts after which the graph was refreshed: a build jk ran that compiled the tree. A verdict's
     * first word is the kind its run journals as ({@link BuildHistoryKinds}), so a gate's reads {@code OK guard}.
     */
    private static final Pattern REFRESHED = Pattern.compile("^OK ("
            + BuildHistoryKinds.ALL.stream()
                    .filter(kind -> !BuildHistoryKinds.isExternalTool(kind))
                    .sorted()
                    .map(Pattern::quote)
                    .collect(Collectors.joining("|"))
            + ")\\b");

    private AnchorsLeft() {}

    /** One place still under an old name. */
    record Place(
            boolean must,
            String place,
            String kind,
            String old,
            String now,
            String rel,
            int line,
            String written,
            String edited) {}

    /**
     * The lines a {@code verdict} for the workspace holding {@code dir} ends with: empty unless it is
     * a build's OK. It waits (a bounded while) for the graph refresh that build started only when a
     * file that held an anchor, or that the record names, changed since the last refresh ({@link
     * AnchorWatch}): otherwise that refresh cannot change the record, which is read at once.
     */
    public static String after(String dir, String verdict) {
        if (!REFRESHED.matcher(verdict).find()) return "";
        try {
            Path root = GraphRunner.rootOf(Path.of(dir));
            if (!GraphRunner.hasGraph(root)) return "";
            String mode = mode(root);
            if (mode.equals(GraphConfig.TAIL_OFF)) return "";
            Path graph = GraphLayout.dir(root);
            if (!AnchorWatch.unchanged(root, graph) && !GraphRunner.awaitRefresh(root, REFRESH_WAIT_MS)) return "";
            List<Place> left = read(graph.resolve("anchors").resolve("stale"));
            if (left.isEmpty()) return "";
            return tail(left, mode);
        } catch (IOException | RuntimeException unreadable) {
            return "";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /**
     * The root manifest's {@code [graph] anchor-tail}: {@link GraphConfig#TAIL_DEFAULT} without parsing it
     * when the root's manifest does not name the key, so a default verdict pays one small read. A directory
     * with no {@code jk.toml} at its root is not a workspace and gets {@link GraphConfig#TAIL_OFF}, as does a
     * root built from its pom.xml, whose shadow manifest has no {@code [graph]} to turn the lines off with.
     */
    static String mode(Path root) throws IOException {
        if (ManifestPaths.isShadowed(root)) return GraphConfig.TAIL_OFF;
        Path manifest = ManifestPaths.manifestIn(root);
        if (!Files.isRegularFile(manifest)) return GraphConfig.TAIL_OFF;
        if (!Files.readString(manifest, StandardCharsets.UTF_8).contains("anchor-tail")) {
            return GraphConfig.TAIL_DEFAULT;
        }
        return GraphRunner.graphConfig(root).anchorTail();
    }

    /** The rows of {@code file}; none when there is no record. */
    static List<Place> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        List<Place> out = new ArrayList<>();
        for (String row : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] f = row.split("\t", -1);
            if (f.length != 9) continue;
            try {
                out.add(new Place(
                        f[0].equals("must"),
                        unesc(f[1]),
                        f[2],
                        unesc(f[3]),
                        unesc(f[4]),
                        unesc(f[5]),
                        Integer.parseInt(f[6]),
                        unesc(f[7]),
                        unesc(f[8])));
            } catch (NumberFormatException skipped) {
                // A row this engine cannot read is not a place it can name.
            }
        }
        return out;
    }

    /**
     * The verdict's lines for {@code left}: the places that must change, by anchor, at most {@link
     * #CAP}, then {@code +N more:} and the call that lists them; and, as {@code mode} asks ({@link
     * GraphConfig#TAIL_COUNT} or {@link GraphConfig#TAIL_KINDS}), the prose that names an old name, as a
     * count. Empty when nothing is to be said.
     */
    static String tail(List<Place> left, String mode) {
        List<Place> must = left.stream().filter(Place::must).toList();
        List<Place> may = left.stream().filter(p -> !p.must()).toList();
        StringBuilder s = new StringBuilder();
        if (!must.isEmpty()) {
            s.append("Your edit changed these names; these places still use the old one:\n");
            Map<String, List<Place>> byAnchor = new LinkedHashMap<>();
            for (Place p : must)
                byAnchor.computeIfAbsent(p.kind() + ":" + p.old(), k -> new ArrayList<>())
                        .add(p);
            int shown = 0;
            List<Place> rest = new ArrayList<>();
            for (Map.Entry<String, List<Place>> e : byAnchor.entrySet()) {
                // One entry per file: a seed file's ten INSERTs naming the column are one place to edit.
                Map<String, List<Place>> byFile = new LinkedHashMap<>();
                for (Place p : e.getValue())
                    byFile.computeIfAbsent(p.rel(), k -> new ArrayList<>()).add(p);
                List<String> at = new ArrayList<>();
                for (List<Place> inFile : byFile.values()) {
                    if (shown == CAP) {
                        rest.addAll(inFile);
                        continue;
                    }
                    int others = inFile.size() - 1;
                    at.add(where(inFile.getFirst())
                            + (others == 0 ? "" : " and " + others + (others == 1 ? " more line" : " more lines")));
                    shown++;
                }
                if (at.isEmpty()) continue;
                Place first = e.getValue().getFirst();
                s.append("  ")
                        .append(e.getKey())
                        .append(first.now().isEmpty() ? ", no longer defined" : ", now " + first.now())
                        .append(": ")
                        .append(String.join(", ", at))
                        .append('\n');
            }
            if (!rest.isEmpty())
                s.append("  +")
                        .append(rest.size())
                        .append(" more: ")
                        .append(more(rest))
                        .append('\n');
        }
        if (!may.isEmpty() && (GraphConfig.TAIL_COUNT.equals(mode) || GraphConfig.TAIL_KINDS.equals(mode))) {
            String count = GraphConfig.TAIL_COUNT.equals(mode) ? places(may.size()) : byKind(may);
            s.append(must.isEmpty() ? "Your edit changed a name the docs still use: " : "Docs use the old name too: ")
                    .append(count)
                    .append(".\n");
        }
        return s.toString();
    }

    /** {@code docker-compose.yml:29 as PETCLINIC_VISITS_MAXPERDAY}: the name as written when it is another anchor's. */
    private static String where(Place p) {
        String at = p.line() > 0 ? p.rel() + ":" + p.line() : p.rel();
        return p.written().isEmpty() ? at : at + " as " + p.written();
    }

    /**
     * The call that lists {@code rest}: callers on the one anchor they share, else on each file whose
     * edit left them, which answers with the places its old names are still used.
     */
    static String more(List<Place> rest) {
        Set<String> anchors = new LinkedHashSet<>();
        Set<String> edited = new LinkedHashSet<>();
        for (Place p : rest) {
            anchors.add(p.kind() + ":" + p.old());
            edited.add(p.edited());
        }
        List<String> calls = new ArrayList<>();
        if (anchors.size() == 1) {
            calls.add(call(anchors.iterator().next()));
        } else {
            for (String f : edited) calls.add(call("file:" + f));
        }
        return String.join(", ", calls);
    }

    private static String call(String address) {
        return "usages(address=\"" + address + "\")";
    }

    /** {@code 2 markdown, 1 text}: the prose places by the kind of file. */
    private static String byKind(List<Place> may) {
        Map<String, Integer> kinds = new TreeMap<>();
        for (Place p : may) kinds.merge(proseKind(p.place()), 1, Integer::sum);
        List<String> out = new ArrayList<>();
        kinds.forEach((k, n) -> out.add(n + " " + k));
        return String.join(", ", out);
    }

    private static String proseKind(String place) {
        return switch (place) {
            case "md", "markdown" -> "markdown";
            case "adoc", "asciidoc" -> "asciidoc";
            case "txt" -> "text";
            default -> place;
        };
    }

    private static String places(int n) {
        return n + (n == 1 ? " place" : " places");
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
