// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The text {@link GraphSearch} matches, folded once per {@link CodeGraph}: each node's label,
 * tokens, camel-case words, source path, attributes and doc summary, plus every term's IDF once
 * asked. Immutable but for the IDF memo, which is safe to share between threads.
 */
final class SearchIndex {

    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    /** One node's searchable text. */
    record Entry(
            GraphNode node,
            String norm,
            String bare,
            String tokens,
            String source,
            String attrs,
            String doc,
            String id,
            List<String> parts,
            List<String> words,
            boolean code) {}

    private final List<Entry> entries;
    private final Map<String, Entry> byId;
    private final Set<String> labels;
    private final int size;
    private final Map<String, Double> idf = new ConcurrentHashMap<>();
    private final AtomicInteger dfPasses = new AtomicInteger();

    SearchIndex(CodeGraph graph) {
        List<Entry> all = new ArrayList<>(graph.size());
        Map<String, Entry> ids = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (GraphNode n : graph.nodes()) {
            Entry e = entry(n);
            all.add(e);
            ids.put(n.id(), e);
            names.add(e.bare().startsWith(".") ? e.bare().substring(1) : e.bare());
        }
        this.entries = List.copyOf(all);
        this.byId = Map.copyOf(ids);
        this.labels = Set.copyOf(names);
        this.size = graph.size();
    }

    private static Entry entry(GraphNode n) {
        String norm = n.normLabel();
        List<String> parts = GraphText.searchTokens(splitCamel(n.label()));
        Object attrs = n.extra().get("attributes");
        String doc = Decl.doc(n);
        return new Entry(
                n,
                norm,
                stripParens(norm),
                String.join(" ", GraphText.searchTokens(n.label())),
                n.sourceFile().toLowerCase(Locale.ROOT),
                attrs == null ? "" : GraphText.fold(String.valueOf(attrs)),
                doc == null ? "" : " " + String.join(" ", GraphText.searchTokens(doc)) + " ",
                n.id().toLowerCase(Locale.ROOT),
                List.copyOf(parts),
                parts.stream().map(GraphSearch::stem).toList(),
                n.fileType().equals("code"));
    }

    List<Entry> entries() {
        return entries;
    }

    @Nullable
    Entry entry(String id) {
        return byId.get(id);
    }

    /** Every node's label folded and stripped of {@code .} and {@code ()}. */
    Set<String> labels() {
        return labels;
    }

    /** {@code log(1 + V / (1 + df))}, {@code df} the labels containing {@code term}. */
    double idf(String term) {
        return idf.computeIfAbsent(term, t -> {
            dfPasses.incrementAndGet();
            long df = entries.stream().filter(e -> e.norm().contains(t)).count();
            return Math.log(1 + (double) Math.max(size, 1) / (1 + df));
        });
    }

    /** How many full passes {@link #idf} has made: one per distinct term over this graph's life. */
    int dfPasses() {
        return dfPasses.get();
    }

    /** {@code OrderService} → {@code Order Service}. */
    static String splitCamel(String label) {
        return CAMEL.matcher(label).replaceAll(" ");
    }

    static String stripParens(String label) {
        int end = label.length();
        while (end > 0 && (label.charAt(end - 1) == '(' || label.charAt(end - 1) == ')')) end--;
        return label.substring(0, end);
    }
}
