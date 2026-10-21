// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lexical node search, graphify's scorer: per query term an exact label match is worth 1000, a
 * prefix 100, a substring 1 and a source-path hit 0.5, each weighted by the term's IDF over labels;
 * the whole query matching a label exactly or as a prefix adds ten times that. Label tiers scale by
 * the square of the share of terms matched. No embeddings.
 */
public final class GraphSearch {

    static final double EXACT = 1000.0;
    static final double PREFIX = 100.0;
    static final double SUBSTRING = 1.0;
    static final double SOURCE = 0.5;
    static final double ATTRIBUTE = 0.75;

    private static final Set<String> STOPWORDS = Set.of(
            "how", "what", "why", "when", "where", "which", "who", "whom", "whose", "does", "did", "is", "are", "was",
            "were", "be", "been", "being", "can", "could", "should", "would", "will", "shall", "may", "might", "must",
            "has", "have", "had", "the", "and", "but", "not", "for", "from", "with", "without", "into", "onto", "off",
            "that", "this", "these", "those", "there", "here", "its", "their", "them", "they", "about", "any", "all",
            "some", "work", "works", "working");

    /** Words that ask about a relation, not a symbol: they never get a guaranteed seed of their own. */
    static final Set<String> RELATIONAL = Set.of(
            "call",
            "calls",
            "called",
            "caller",
            "callers",
            "invoke",
            "invokes",
            "invoked",
            "use",
            "uses",
            "used",
            "using",
            "import",
            "imports",
            "imported",
            "export",
            "exports",
            "exported",
            "extend",
            "extends",
            "extended",
            "implement",
            "implements",
            "implemented",
            "depend",
            "depends",
            "reference",
            "references",
            "referenced");

    private final CodeGraph graph;
    private final Map<String, Double> idf = new HashMap<>();

    public GraphSearch(CodeGraph graph) {
        this.graph = graph;
    }

    /** Ranked matches plus, per term, its single best node. */
    record Scores(List<Scored> ranked, Map<String, String> bestByTerm, List<String> termOrder) {}

    record Scored(double score, String id) {}

    /** Searchable terms of {@code question}: words over two letters, stopwords dropped unless that empties it. */
    static List<String> queryTerms(String question) {
        List<String> terms = new ArrayList<>();
        for (String raw : question.split("\\s+")) {
            for (String tok : wordTokens(raw.toLowerCase(Locale.ROOT))) {
                if (searchable(tok)) terms.add(tok);
            }
        }
        List<String> content =
                terms.stream().filter(t -> !STOPWORDS.contains(t)).toList();
        return content.isEmpty() ? terms : content;
    }

    /** Nodes matching {@code question}, best first. */
    public List<String> rank(String question) {
        return score(queryTerms(question)).ranked().stream().map(Scored::id).toList();
    }

    /** At most three seeds within 20% of the top score, then one per term that has a match. */
    List<String> seeds(String question) {
        Scores s = score(queryTerms(question));
        Map<String, String> best = new HashMap<>(s.bestByTerm());
        List<String> terms = queryTerms(question);
        boolean intent = best.keySet().stream().anyMatch(RELATIONAL::contains);
        if (intent && terms.stream().anyMatch(t -> !RELATIONAL.contains(t)))
            best.keySet().removeIf(RELATIONAL::contains);
        return pickSeeds(s.ranked(), best, s.termOrder());
    }

    Scores score(List<String> terms) {
        List<String> norm = new ArrayList<>(new LinkedHashSet<>(
                terms.stream().flatMap(t -> GraphText.searchTokens(t).stream()).toList()));
        String joined = String.join(" ", norm);
        double joinedWeight = norm.stream().mapToDouble(this::idf).max().orElse(1.0);
        List<Scored> ranked = new ArrayList<>();
        Map<String, BestSeed> best = new HashMap<>();
        for (GraphNode n : graph.nodes()) {
            double score = scoreNode(n, norm, joined, joinedWeight, best);
            if (score > 0) ranked.add(new Scored(score, n.id()));
        }
        ranked.sort(Comparator.comparingDouble((Scored s) -> -s.score())
                .thenComparingInt(s -> labelLength(s.id()))
                .thenComparing(Scored::id));
        Map<String, String> bestByTerm = new HashMap<>();
        best.forEach((t, b) -> bestByTerm.put(t, b.id()));
        List<String> order = best.entrySet().stream()
                .sorted(Map.Entry.<String, BestSeed>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();
        return new Scores(ranked, bestByTerm, order);
    }

    private double scoreNode(
            GraphNode n, List<String> terms, String joined, double joinedWeight, Map<String, BestSeed> best) {
        String label = n.normLabel();
        String bare = stripParens(label);
        String tokens = String.join(" ", GraphText.searchTokens(n.label()));
        String source = n.sourceFile().toLowerCase(Locale.ROOT);
        String attrs = attributeText(n);
        String id = n.id().toLowerCase(Locale.ROOT);
        double score = 0;
        if (!joined.isEmpty()) {
            if (joined.equals(label) || joined.equals(bare) || joined.equals(tokens) || joined.equals(id)) {
                score += EXACT * 10 * joinedWeight;
            } else if (label.startsWith(joined) || bare.startsWith(joined) || tokens.startsWith(joined)) {
                score += PREFIX * 10 * joinedWeight;
            }
        }
        int matched = 0;
        double tiered = 0;
        for (String t : terms) {
            double w = idf(t);
            double tier = 0;
            double flat = 0;
            if (t.equals(label) || t.equals(bare)) {
                tier = EXACT * w;
                matched++;
            } else if (label.startsWith(t) || bare.startsWith(t)) {
                tier = PREFIX * w;
                matched++;
            } else if (label.contains(t)) {
                flat += SUBSTRING * w;
                matched++;
            }
            if (source.contains(t)) flat += SOURCE * w;
            if (!attrs.isEmpty() && attrs.contains(t)) flat += ATTRIBUTE * w;
            score += flat;
            tiered += tier;
            double singleton = tier + flat;
            if (t.equals(label) || t.equals(bare) || t.equals(tokens) || t.equals(id)) {
                singleton += EXACT * 10 * w;
            } else if (label.startsWith(t) || bare.startsWith(t) || tokens.startsWith(t)) {
                singleton += PREFIX * 10 * w;
            }
            if (singleton > 0) {
                BestSeed candidate = new BestSeed(
                        -singleton, -graph.degree(n.id()), n.label().length(), n.id());
                best.merge(t, candidate, (a, b) -> a.compareTo(b) <= 0 ? a : b);
            }
        }
        if (tiered > 0) score += tiered * Math.pow((double) matched / terms.size(), 2);
        return score;
    }

    private List<String> pickSeeds(List<Scored> ranked, Map<String, String> best, List<String> termOrder) {
        if (ranked.isEmpty()) return List.of();
        double top = ranked.getFirst().score();
        List<String> seeds = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (Scored s : ranked) {
            if (seeds.size() >= 3 || (!seeds.isEmpty() && s.score() < top * 0.2)) break;
            if (labels.add(seedKey(s.id()))) seeds.add(s.id());
        }
        List<String> ordered =
                new ArrayList<>(termOrder.stream().filter(best::containsKey).toList());
        best.keySet().stream().filter(t -> !ordered.contains(t)).sorted().forEach(ordered::add);
        for (String term : ordered) {
            String id = best.get(term);
            if (id != null && !seeds.contains(id) && labels.add(seedKey(id))) seeds.add(id);
        }
        return seeds;
    }

    private double idf(String term) {
        return idf.computeIfAbsent(term, t -> {
            long df = graph.nodes().stream()
                    .filter(n -> n.normLabel().contains(t))
                    .count();
            return Math.log(1 + (double) Math.max(graph.size(), 1) / (1 + df));
        });
    }

    private String seedKey(String id) {
        GraphNode n = graph.node(id);
        return n == null || n.normLabel().isEmpty() ? id : n.normLabel();
    }

    private int labelLength(String id) {
        GraphNode n = graph.node(id);
        return n == null ? id.length() : n.label().length();
    }

    private static String attributeText(GraphNode n) {
        Object attrs = n.extra().get("attributes");
        return attrs == null ? "" : GraphText.fold(String.valueOf(attrs));
    }

    private static String stripParens(String label) {
        int end = label.length();
        while (end > 0 && (label.charAt(end - 1) == '(' || label.charAt(end - 1) == ')')) end--;
        return label.substring(0, end);
    }

    private static boolean searchable(String term) {
        return !term.chars().allMatch(c -> c >= 'a' && c <= 'z') || term.length() > 2;
    }

    private static List<String> wordTokens(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (GraphText.isWord(cp)) {
                cur.appendCodePoint(cp);
            } else if (!cur.isEmpty()) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        });
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    /** Tie-break for a term's best seed: score, then degree, then shorter label, then id. */
    private record BestSeed(double negScore, int negDegree, int labelLength, String id)
            implements Comparable<BestSeed> {
        @Override
        public int compareTo(BestSeed o) {
            return Comparator.comparingDouble(BestSeed::negScore)
                    .thenComparingInt(BestSeed::negDegree)
                    .thenComparingInt(BestSeed::labelLength)
                    .thenComparing(BestSeed::id)
                    .compare(this, o);
        }
    }
}
