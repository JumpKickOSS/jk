// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Budget;
import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.GraphSearch;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.codegraph.SymbolFinder;
import cc.jumpkick.codegraph.extract.Extractor;
import cc.jumpkick.codegraph.extract.Extractors;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A question answered over the public API of the locked jars a library selector picks. Each public
 * type and each public member name is a node whose name, and Javadoc summary when the jar's sources
 * jar is on disk, the question's words are matched against as {@code query} matches the workspace;
 * the answer lists the best matches with their signatures, best first, within the budget. A jar's
 * API is indexed the first time a question asks about it and kept beside its type names.
 */
final class LibraryQuery {

    /** Overloads shown under one member before the rest are counted. */
    static final int OVERLOADS = 4;

    /** Matches shown with one member name before the rest are counted. */
    static final int SAME_NAME = 3;

    /** Room kept for the line that counts the matches. */
    private static final int SUMMARY = 160;

    /** Index rows by key, kept while the worker is served. */
    private static final Map<String, List<String>> MEMORY = new ConcurrentHashMap<>();

    /** Search graphs by the keys of the jars they hold, the last few kept while the worker is served. */
    private static final Map<String, Api> GRAPHS = new ConcurrentHashMap<>();

    /** Search graphs kept at most. */
    private static final int KEPT = 8;

    /** Jars named one line each in an answer's head; more are counted. */
    private static final int NAMED = 3;

    /**
     * The searchable API of some jars: the graph of their types and member names, each node's
     * signatures and Javadoc summary, and how many nodes there are.
     */
    private record Api(CodeGraph graph, Map<String, Entry> entries, int size) {}

    /**
     * One node's answer: the name it is shown as, its member's name ({@code ""} for a type), its
     * signatures, its Javadoc summary, and its jar's coordinate when the question asked several.
     */
    private record Entry(String name, String member, List<String> signatures, String doc, String coord) {}

    private final LibraryApi api;

    LibraryQuery(LibraryApi api) {
        this.api = api;
    }

    /** The public types and members of the jars {@code library} selects that match {@code question}, within {@code budget}. */
    String answer(String library, String question, int budget) {
        if (api.empty()) return "No locked jar is on this machine: jk sync fetches them.";
        List<LibraryApi.Jar> jars = select(library);
        if (jars.isEmpty()) return noLibrary(library);
        List<String> keys = new ArrayList<>();
        for (LibraryApi.Jar jar : jars) keys.add(key(jar));
        String graphKey = String.join(",", keys);
        if (!GRAPHS.containsKey(graphKey) && GRAPHS.size() >= KEPT) GRAPHS.clear();
        Api found = GRAPHS.computeIfAbsent(graphKey, k -> build(jars, keys));
        List<String> head = head(library, jars);
        GraphSearch search = new GraphSearch(found.graph());
        List<String> ranked = search.rank(question);
        if (ranked.isEmpty()) {
            List<String> nearest = search.nearest(question, 8);
            head.addFirst("No public type or member of "
                    + String.join(", ", jars.stream().map(LibraryApi.Jar::coord).toList()) + " matches '" + question
                    + "'.");
            if (!nearest.isEmpty()) head.add("Closest names: " + String.join(", ", nearest));
            return String.join("\n", head);
        }
        return render(head, found, ranked, budget);
    }

    /** The lines that open an answer: which locked versions were read, and whether their Javadoc was. */
    private static List<String> head(String library, List<LibraryApi.Jar> jars) {
        List<String> head = new ArrayList<>();
        if (jars.size() > NAMED) {
            long sources = jars.stream().filter(j -> j.sources() != null).count();
            head.add("[i] " + jars.size() + " locked jars match '" + library + "'; " + sources
                    + " have a sources jar on this machine, whose Javadoc summaries were matched too.");
            return head;
        }
        for (LibraryApi.Jar jar : jars) {
            head.add("[i] " + jar.coord() + " is the locked version; "
                    + (jar.sources() == null
                            ? "no sources jar on this machine, so names alone were matched (jk sync --sources"
                                    + " fetches one when published)."
                            : "names and Javadoc summaries from its sources jar were matched."));
        }
        return head;
    }

    /**
     * {@code head}, then as many of the {@code ranked} matches as {@code budget} holds, each whole.
     * A member name already shown {@link #SAME_NAME} times is counted instead of shown again: a
     * question about strings matches every type's {@code toString}.
     */
    private static String render(List<String> head, Api found, List<String> ranked, int budget) {
        int room = budget * Budget.CHARS_PER_TOKEN - String.join("\n", head).length() - SUMMARY;
        List<String> body = new ArrayList<>();
        Map<String, Integer> shown = new HashMap<>();
        Map<String, Integer> folded = new LinkedHashMap<>();
        int blocks = 0;
        int matches = 0;
        for (String id : ranked) {
            Entry e = found.entries().get(id);
            if (e == null) continue;
            if (!e.member().isEmpty() && shown.getOrDefault(e.member(), 0) >= SAME_NAME) {
                folded.merge(e.member(), 1, Integer::sum);
                matches++;
                continue;
            }
            List<String> block = block(e);
            int size = String.join("\n", block).length() + 2;
            if (blocks > 0 && size > room) break;
            if (!e.member().isEmpty()) shown.merge(e.member(), 1, Integer::sum);
            matches++;
            body.add("");
            body.addAll(block);
            room -= size;
            blocks++;
        }
        int left = ranked.size() - matches;
        List<String> out = new ArrayList<>(head);
        out.add(
                left == 0
                        ? plural(matches, "match") + " among " + apiSize(found) + ", best first."
                        : "The best " + plural(matches, "match") + " of "
                                + String.format(Locale.ROOT, "%,d", ranked.size()) + " among " + apiSize(found)
                                + "; a larger budget shows more.");
        out.addAll(body);
        if (!folded.isEmpty()) {
            List<String> names = new ArrayList<>();
            folded.forEach((name, n) -> names.add(name + " on " + plural(n, "more type")));
            out.add("");
            out.add("Also matched: " + String.join(", ", names) + ".");
        }
        return Budget.lines(out, budget, "Name one member: jk graph read Type.member");
    }

    /** A match: its qualified name, its Javadoc summary, then its signatures, the overloads past {@link #OVERLOADS} counted. */
    private static List<String> block(Entry e) {
        List<String> out = new ArrayList<>();
        out.add(e.coord().isEmpty() ? e.name() : e.name() + "  (" + e.coord() + ")");
        if (!e.doc().isEmpty()) out.add("  " + e.doc());
        List<String> sigs = e.signatures();
        for (String sig : sigs.subList(0, Math.min(OVERLOADS, sigs.size()))) out.add("  " + sig);
        if (sigs.size() > OVERLOADS) {
            out.add("  … " + plural(sigs.size() - OVERLOADS, "more overload") + ": jk graph read " + e.name());
        }
        return out;
    }

    private static String apiSize(Api found) {
        return String.format(Locale.ROOT, "%,d public types and member names", found.size());
    }

    private static String plural(int n, String word) {
        return String.format(Locale.ROOT, "%,d %s", n, n == 1 ? word : word.endsWith("ch") ? word + "es" : word + "s");
    }

    /**
     * The jars {@code library} names: a glob matched, case-insensitively, against each locked
     * coordinate whole, its {@code group:artifact} and its artifact.
     */
    List<LibraryApi.Jar> select(String library) {
        Pattern glob = SymbolFinder.namePattern(library.strip());
        List<LibraryApi.Jar> out = new ArrayList<>();
        for (LibraryApi.Jar jar : api.jars()) {
            String[] parts = jar.coord().split(":");
            String ga = parts.length > 1 ? parts[0] + ":" + parts[1] : jar.coord();
            String artifact = parts.length > 1 ? parts[1] : jar.coord();
            if (glob.matcher(jar.coord()).matches()
                    || glob.matcher(ga).matches()
                    || glob.matcher(artifact).matches()) {
                out.add(jar);
            }
        }
        return out;
    }

    /** No locked jar matches {@code library}: the answer names the locked ones whose coordinate contains it, else all. */
    private String noLibrary(String library) {
        String needle = library.replace("*", "").replace("?", "").toLowerCase(Locale.ROOT);
        List<String> coords =
                api.jars().stream().map(LibraryApi.Jar::coord).sorted().toList();
        List<String> close = coords.stream()
                .filter(c -> !needle.isEmpty() && c.toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        List<String> shown = close.isEmpty() ? coords : close;
        List<String> out = new ArrayList<>();
        out.add("No locked library matches '" + library + "'. Name one by artifact, group:artifact or a glob:");
        shown.subList(0, Math.min(10, shown.size())).forEach(c -> out.add("  " + c));
        if (shown.size() > 10) out.add("  … " + (shown.size() - 10) + " more");
        return String.join("\n", out);
    }

    /** The search graph of {@code jars}, whose index keys are {@code keys}. */
    private Api build(List<LibraryApi.Jar> jars, List<String> keys) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Map<String, Entry> entries = new HashMap<>();
        Map<String, String> owners = new HashMap<>();
        Map<String, List<String>> signatures = new LinkedHashMap<>();
        Map<String, String[]> first = new LinkedHashMap<>();
        Map<String, String> coords = new HashMap<>();
        for (int i = 0; i < jars.size(); i++) {
            LibraryApi.Jar jar = jars.get(i);
            String prefix = jars.size() > 1 ? jar.coord() + "|" : "";
            for (String row : rows(jar, keys.get(i))) {
                String[] f = row.split("\t", -1);
                if (f.length != 5) continue;
                String typeId = prefix + f[0];
                String id = f[1].isEmpty() ? typeId : typeId + "#" + f[1];
                signatures.computeIfAbsent(id, k -> new ArrayList<>()).add(f[2]);
                first.putIfAbsent(id, f);
                if (!prefix.isEmpty()) coords.put(id, jar.coord());
                if (!f[1].isEmpty()) owners.put(id, typeId);
            }
        }
        first.forEach((id, f) -> {
            String binary = f[0];
            String dotted = binary.replace('$', '.');
            String simple = binary.substring(Math.max(binary.lastIndexOf('.'), binary.lastIndexOf('$')) + 1);
            int dot = binary.lastIndexOf('.');
            String file = (dot < 0 ? "" : binary.substring(0, dot).replace('.', '/') + "/") + simple + ".java";
            boolean member = !f[1].isEmpty();
            String label = member ? "." + f[1] + ("m".equals(f[3]) ? "()" : "") : simple;
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put(Decl.KIND, member ? ("m".equals(f[3]) ? "method" : "property") : "class");
            facts.put(Decl.VIS, "public");
            if (!f[4].isEmpty()) facts.put(Decl.DOC, f[4]);
            nodes.add(new GraphNode(id, label, "code", file, "", Map.of(Decl.KEY, facts)));
            entries.put(
                    id,
                    new Entry(
                            member ? dotted + "." + f[1] : dotted,
                            f[1],
                            signatures.getOrDefault(id, List.of()),
                            f[4],
                            coords.getOrDefault(id, "")));
            String owner = owners.get(id);
            if (owner != null) edges.add(GraphEdge.of(owner, id, Relations.METHOD, Confidence.EXTRACTED, file, 0));
        });
        return new Api(CodeGraph.merge(List.of(new Fragment(nodes, edges))), Map.copyOf(entries), first.size());
    }

    /**
     * {@code binary \t member \t signature \t m|f|t \t doc} for each public type ({@code member}
     * empty, {@code t}) and each public method ({@code m}) or field ({@code f}) of {@code jar}:
     * from memory, else the index kept under {@code key}, else read from the jar and kept.
     */
    private List<String> rows(LibraryApi.Jar jar, String key) {
        List<String> held = MEMORY.get(key);
        if (held != null) return held;
        Path cache = api.cache();
        Path file = cache == null ? null : cache.resolve(key + ".api");
        if (file != null && Files.isRegularFile(file)) {
            try {
                List<String> read = Files.readAllLines(file, StandardCharsets.UTF_8);
                MEMORY.put(key, read);
                return read;
            } catch (IOException unreadable) {
                Log.debug("graph: library API index unreadable at " + file, unreadable);
            }
        }
        List<String> rows = index(jar);
        MEMORY.put(key, rows);
        if (file != null) {
            try {
                Files.createDirectories(file.getParent());
                AtomicWrites.replace(file, String.join("\n", rows) + "\n");
            } catch (IOException unwritable) {
                Log.debug("graph: cannot keep library API index at " + file, unwritable);
            }
        }
        return rows;
    }

    /** The index rows of {@code jar}, read from its class files and, for Javadoc, its sources jar. */
    private List<String> index(LibraryApi.Jar jar) {
        List<String> out = new ArrayList<>();
        Map<String, Map<String, String>> docsByFile = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.path().toFile());
                ZipFile sources =
                        jar.sources() == null ? null : new ZipFile(jar.sources().toFile())) {
            for (LibraryApi.Type t : api.types()) {
                if (t.jar() != jar) continue;
                ZipEntry e = zip.getEntry(t.binary().replace('.', '/') + ".class");
                if (e == null) continue;
                ClassModel m;
                try (InputStream in = zip.getInputStream(e)) {
                    m = ClassFile.of().parse(in.readAllBytes());
                } catch (IllegalArgumentException malformed) {
                    Log.debug("graph: unreadable class " + t.binary() + " in " + jar.path(), malformed);
                    continue;
                }
                Map<String, String> docs = sources == null
                        ? Map.of()
                        : docsByFile.computeIfAbsent(LibraryApi.sourceFile(t, m), f -> docs(sources, f));
                String owner = owner(t);
                out.add(row(t.binary(), "", LibraryApi.header(t, m), "t", docs.getOrDefault(owner, "")));
                for (LibraryApi.Member member : LibraryApi.members(t, m)) {
                    out.add(row(
                            t.binary(),
                            member.name(),
                            member.signature(),
                            member.method() ? "m" : "f",
                            docs.getOrDefault(owner + "#" + member.name(), "")));
                }
            }
        } catch (IOException unreadable) {
            Log.debug("graph: unreadable jar " + jar.path(), unreadable);
        }
        return out;
    }

    /** {@code t}'s name within its package, nested types joined by {@code .}: {@code Mapper.Feature}. */
    private static String owner(LibraryApi.Type t) {
        String dotted = t.dotted();
        int dot = t.binary().lastIndexOf('.');
        return dot < 0 ? dotted : dotted.substring(dot + 1);
    }

    private static String row(String binary, String member, String signature, String kind, String doc) {
        return binary + "\t" + member + "\t" + flat(signature) + "\t" + kind + "\t" + flat(doc);
    }

    private static String flat(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    /**
     * The Javadoc summaries of the source {@code file} in {@code sources}, by {@code Type} and {@code
     * Type#member} as {@link #owner} names them; empty when the file is absent or not a JVM source.
     */
    private static Map<String, String> docs(ZipFile sources, String file) {
        Extractor extractor = Extractors.forPath(file);
        ZipEntry e = sources.getEntry(file);
        if (extractor == null || !Extractors.isJvmSource(file) || e == null) return Map.of();
        String text;
        try (InputStream in = sources.getInputStream(e)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return Map.of();
        }
        CodeGraph g = Linker.link(List.of(extractor.extract(file, text)));
        Map<String, String> out = new HashMap<>();
        for (GraphNode n : g.nodes()) {
            String doc = Decl.doc(n);
            if (doc == null || doc.isBlank()) continue;
            if (Decl.isType(n)) {
                out.putIfAbsent(g.display(n.id()), doc);
            } else if (n.label().startsWith(".")) {
                GraphNode owner = g.owner(n.id());
                if (owner == null) continue;
                String name = n.label().substring(1).replace("()", "");
                out.putIfAbsent(g.display(owner.id()) + "#" + name, doc);
            }
        }
        return out;
    }

    /**
     * The key {@code jar}'s index is kept under: the jar's and its sources jar's path, size and
     * modification time, so a sources jar fetched later indexes it again with its Javadoc.
     */
    private static String key(LibraryApi.Jar jar) {
        StringBuilder id = new StringBuilder("api\t");
        for (Path p : jar.sources() == null ? List.of(jar.path()) : List.of(jar.path(), jar.sources())) {
            try {
                id.append(p.toAbsolutePath())
                        .append('\t')
                        .append(Files.size(p))
                        .append('\t')
                        .append(Files.getLastModifiedTime(p).toMillis())
                        .append('\t');
            } catch (IOException gone) {
                id.append(p.toAbsolutePath()).append("\tgone\t");
            }
        }
        return Hashing.sha256Hex(id.toString());
    }
}
