// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.OutputDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The reader half of the reference index jk's javac writes during every compile (the Java
 * compiler plugin's {@code References}): for one source, every name that resolves to a type,
 * method, constructor, field, enum constant or record component, by the symbol the class files name
 * and the exact span of the name. A file is used only while its header's SHA-256 is the source's as
 * it stands; anything else reads as no index, and the caller falls back to what it did without one.
 */
final class ReferenceIndex {

    private ReferenceIndex() {}

    static final String HEADER = "# jk-refs 2 ";

    /** The source sets' index directories, as the writer names them after the class outputs. */
    private static final List<String> SETS = List.of("classes-refs", "test-classes-refs");

    /** One recorded name: {@code d} declaration, {@code r} reference, {@code j} Javadoc reference. */
    record Span(char kind, String symbol, int start, int end, int line) {}

    /** The symbol of method {@code name} with {@code desc} declared by internal name {@code owner}. */
    static String method(String owner, String name, String desc) {
        return "M " + owner.replace('/', '.') + "#" + name + desc;
    }

    /**
     * One file renamed from its index: the new text, the offsets of the declarations it renamed (for
     * the caller's override check), how many calls and doc links, and the lines touched.
     */
    record Edit(String text, List<Integer> declarations, int calls, int docs, List<Integer> lines) {}

    /**
     * {@code text} with every span of one of {@code symbols} renamed from {@code old} to {@code to};
     * {@code null} when the index cannot be trusted for it: fewer calls than the classes record
     * ({@code recordedCalls}), or a span that no longer spells {@code old} or overlaps another.
     */
    static @Nullable Edit rename(
            List<Span> spans, String text, Set<String> symbols, String old, String to, long recordedCalls) {
        List<Span> mine = spans.stream()
                .filter(sp -> symbols.contains(sp.symbol()))
                .sorted(Comparator.comparingInt(Span::start))
                .toList();
        if (mine.stream().filter(sp -> sp.kind() == 'r').count() < recordedCalls) return null;
        int last = 0;
        for (Span sp : mine) {
            if (sp.start() < last || sp.end() - sp.start() != old.length() || !text.startsWith(old, sp.start())) {
                return null;
            }
            last = sp.end();
        }
        StringBuilder out = new StringBuilder(text.length());
        List<Integer> declarations = new ArrayList<>();
        int calls = 0;
        int docs = 0;
        int at = 0;
        for (Span sp : mine) {
            out.append(text, at, sp.start()).append(to);
            at = sp.end();
            switch (sp.kind()) {
                case 'd' -> declarations.add(sp.start());
                case 'j' -> docs++;
                default -> calls++;
            }
        }
        out.append(text, at, text.length());
        List<Integer> lines = mine.stream().map(Span::line).distinct().sorted().toList();
        return new Edit(out.toString(), declarations, calls, docs, lines);
    }

    /**
     * The spans of a version-2 index body: {@code =<symbol>} lines number the symbols, and each
     * {@code <kind><id>\t<delta>} line is a name starting {@code delta} characters after the last;
     * its end is the start plus the symbol's simple name, its line counted in {@code text}.
     */
    static @Nullable List<Span> decode(List<String> body, String text) {
        List<String> symbols = new ArrayList<>();
        List<Span> spans = new ArrayList<>(body.size());
        int[] lineStarts = lineStarts(text);
        int start = 0;
        for (String line : body) {
            if (line.startsWith("=")) {
                symbols.add(line.substring(1));
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 2) return null;
            int id = Integer.parseInt(line.substring(1, tab));
            if (id >= symbols.size()) return null;
            String symbol = symbols.get(id);
            start += Integer.parseInt(line.substring(tab + 1));
            int end = start + simpleName(symbol).length();
            if (start < 0 || end > text.length()) return null;
            spans.add(new Span(line.charAt(0), symbol, start, end, lineOf(lineStarts, start)));
        }
        return spans;
    }

    /** The name {@code symbol} is spelled with in source. */
    static String simpleName(String symbol) {
        int hash = symbol.indexOf('#');
        if (hash >= 0) {
            int paren = symbol.indexOf('(', hash);
            return symbol.substring(hash + 1, paren < 0 ? symbol.length() : paren);
        }
        String binary = symbol.length() > 2 ? symbol.substring(2) : symbol;
        String simple = binary.substring(binary.lastIndexOf('.') + 1);
        return simple.substring(simple.lastIndexOf('$') + 1);
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') starts.add(i + 1);
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The 1-based line holding {@code offset}. */
    private static int lineOf(int[] starts, int offset) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= offset) lo = mid;
            else hi = mid - 1;
        }
        return lo + 1;
    }

    /**
     * The spans recorded for root-relative {@code rel}, whose current content is {@code text}, or
     * {@code null} when no index file describes it as it is.
     */
    static @Nullable List<Span> of(Path root, Modules modules, String rel, String text) {
        Modules.Module m = modules.of(rel);
        String prefix = m == null ? "" : m.prefix();
        if (!rel.startsWith(prefix)) return null;
        Path incremental = root.resolve(prefix).resolve(OutputDirs.TARGET).resolve("incremental");
        String header = HEADER + Hashing.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
        for (String set : SETS) {
            Path file = incremental.resolve(set).resolve(rel.substring(prefix.length()) + ".refs");
            if (!Files.isRegularFile(file)) continue;
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.isEmpty() || !lines.getFirst().equals(header)) return null;
                return decode(lines.subList(1, lines.size()), text);
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }
        return null;
    }
}
