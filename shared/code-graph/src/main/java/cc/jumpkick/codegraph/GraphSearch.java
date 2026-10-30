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
import org.jspecify.annotations.Nullable;

/**
 * Lexical node search: per query term an exact label match is worth 1000, a
 * prefix 100, a substring 1 and a source-path hit 0.5, each weighted by the term's IDF over labels;
 * the whole query matching a label exactly or as a prefix adds ten times that. Label tiers scale by
 * the square of the share of terms matched. A word of a declaration's doc summary is worth 0.6. No
 * embeddings.
 */
public final class GraphSearch {

    static final double EXACT = 1000.0;
    static final double PREFIX = 100.0;
    static final double SUBSTRING = 1.0;
    static final double SOURCE = 0.5;
    static final double ATTRIBUTE = 0.75;
    static final double DOC = 0.6;

    /**
     * A query word that is one whole camel-case word of a name ({@code errors} in {@code ApiErrors},
     * {@code exception} in {@code ExceptionHandler}): half a prefix match, since the name says the
     * same thing a word later.
     */
    static final double WORD = PREFIX / 2;

    /** A non-code node (a config key, a dependency, a heading) scores this share of a declaration's tier. */
    static final double NON_CODE = 0.25;

    /**
     * Words a question uses for what code names otherwise: "which endpoint returns …" asks for a
     * controller's mapping. Each also searches as its expansions.
     */
    static final Map<String, List<String>> VOCABULARY = Map.of(
            "endpoint", List.of("controller", "mapping"),
            "endpoints", List.of("controller", "mapping"),
            "route", List.of("controller", "mapping"),
            "routes", List.of("controller", "mapping"),
            "login", List.of("signin", "authentication"),
            "signin", List.of("login", "authentication"));

    /** Words that make a question about tests, where tests are seeds too. */
    private static final Set<String> TEST_WORDS = Set.of("test", "tests", "spec", "specs", "tested", "testing");

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
    private final Scope scope;
    private final Map<String, Double> idf = new HashMap<>();

    public GraphSearch(CodeGraph graph) {
        this(graph, Scope.ALL);
    }

    /** A search that only finds nodes in {@code scope}. */
    public GraphSearch(CodeGraph graph, Scope scope) {
        this.graph = graph;
        this.scope = scope;
    }

    /** Ranked matches plus, per term, its single best node. */
    record Scores(List<Scored> ranked, Map<String, String> bestByTerm, List<String> termOrder) {}

    record Scored(double score, String id) {}

    /**
     * Searchable terms of {@code question}: words over two letters, stopwords dropped unless that
     * empties it, each {@link #VOCABULARY} word followed by its expansions.
     */
    static List<String> queryTerms(String question) {
        List<String> terms = new ArrayList<>();
        for (String raw : question.split("\\s+")) {
            for (String tok : wordTokens(raw.toLowerCase(Locale.ROOT))) {
                if (searchable(tok)) {
                    terms.add(tok);
                    for (String more : VOCABULARY.getOrDefault(tok, List.of()))
                        if (!terms.contains(more)) terms.add(more);
                }
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

    /**
     * At most three seeds within 20% of the top score, then one per term that has a match. Two or
     * three adjacent words that spell a symbol's name together ({@code format worker} for {@code
     * FormatWorker}) count as that one term: in camel-cased code the compound is what was meant.
     */
    List<String> seeds(String question) {
        List<String> terms = new ArrayList<>(compounds(queryTerms(question)));
        if (terms.stream().anyMatch(t -> !RELATIONAL.contains(t))) terms.removeIf(RELATIONAL::contains);
        List<String> members = members(terms);
        Scores s = score(terms);
        List<String> seeds = new ArrayList<>(members);
        for (String id : pickSeeds(s.ranked(), new HashMap<>(s.bestByTerm()), s.termOrder())) {
            if (!seeds.contains(id) && seeds.size() < 3 + members.size()) seeds.add(id);
        }
        return seeds;
    }

    /**
     * A type's name followed by one of its members' ({@code FactsIndexing ensure}): that member,
     * which a word-by-word score cannot find because neither word alone names it.
     */
    List<String> members(List<String> terms) {
        Map<String, List<String>> typesByLabel = new HashMap<>();
        for (GraphNode n : graph.nodes()) {
            if (n.fileType().equals("code") && !n.label().endsWith(")") && scope.contains(n)) {
                typesByLabel
                        .computeIfAbsent(n.normLabel(), k -> new ArrayList<>())
                        .add(n.id());
            }
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 1 < terms.size(); i++) {
            for (String type : typesByLabel.getOrDefault(terms.get(i), List.of())) {
                for (String member : graph.neighbors(type)) {
                    GraphNode m = graph.node(member);
                    if (m != null
                            && stripParens(m.normLabel()).equals("." + terms.get(i + 1))
                            && !out.contains(member)) {
                        out.add(member);
                    }
                }
            }
        }
        return out;
    }

    /** {@code terms} with every run of two or three that names a symbol joined into one. */
    List<String> compounds(List<String> terms) {
        if (terms.size() < 2) return terms;
        Set<String> labels = labels();
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < terms.size()) {
            int taken = 1;
            for (int width = Math.min(3, terms.size() - i); width >= 2; width--) {
                String joined = String.join("", terms.subList(i, i + width));
                if (labels.contains(joined)) {
                    out.add(joined);
                    taken = width;
                    break;
                }
            }
            if (taken == 1) out.add(terms.get(i));
            i += taken;
        }
        return out;
    }

    private @Nullable Set<String> labels;

    /** Whether the question being scored asks about tests. */
    private boolean aboutTests;

    /** Every code node's label folded and stripped of {@code .} and {@code ()}. */
    private Set<String> labels() {
        Set<String> have = labels;
        if (have != null) return have;
        Set<String> built = new HashSet<>();
        for (GraphNode n : graph.nodes()) {
            String l = stripParens(n.normLabel());
            built.add(l.startsWith(".") ? l.substring(1) : l);
        }
        labels = built;
        return built;
    }

    Scores score(List<String> terms) {
        aboutTests = terms.stream().anyMatch(TEST_WORDS::contains);
        List<String> norm = new ArrayList<>(new LinkedHashSet<>(
                terms.stream().flatMap(t -> GraphText.searchTokens(t).stream()).toList()));
        String joined = String.join(" ", norm);
        double joinedWeight = norm.stream().mapToDouble(this::idf).max().orElse(1.0);
        List<Scored> ranked = new ArrayList<>();
        Map<String, BestSeed> best = new HashMap<>();
        for (GraphNode n : graph.nodes()) {
            if (!scope.contains(n)) continue;
            // A question that does not ask about tests does not start at one: a test named as a
            // sentence shares words with every question about the code it tests. The walk still
            // reaches tests through their edges.
            if (!aboutTests && isTest(n)) continue;
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
        String doc = docText(n);
        String id = n.id().toLowerCase(Locale.ROOT);
        List<String> words = GraphText.searchTokens(splitCamel(n.label())).stream()
                .map(GraphSearch::stem)
                .toList();
        double kind = n.fileType().equals("code") ? 1.0 : NON_CODE;
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
            String st = stem(t);
            double tier = 0;
            double flat = 0;
            if (t.equals(label) || t.equals(bare)) {
                tier = EXACT * w * kind;
                matched++;
            } else if (label.startsWith(t) || bare.startsWith(t)) {
                tier = PREFIX * w * kind;
                matched++;
            } else if (st.length() >= 3 && words.stream().anyMatch(word -> sameWord(st, word))) {
                tier = WORD * w * kind;
                matched++;
            } else if (label.contains(t)) {
                flat += SUBSTRING * w;
                matched++;
            }
            if (source.contains(t)) flat += SOURCE * w;
            if (!attrs.isEmpty() && attrs.contains(st)) flat += ATTRIBUTE * w;
            if (!doc.isEmpty() && doc.contains(" " + st)) flat += DOC * w;
            score += flat;
            tiered += tier;
            double singleton = tier + flat;
            if (t.equals(label) || t.equals(bare) || t.equals(tokens) || t.equals(id)) {
                singleton += EXACT * 10 * w * kind;
            } else if (label.startsWith(t) || bare.startsWith(t) || tokens.startsWith(t)) {
                singleton += PREFIX * 10 * w * kind;
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

    private static String docText(GraphNode n) {
        String doc = Decl.doc(n);
        return doc == null ? "" : " " + String.join(" ", GraphText.searchTokens(doc)) + " ";
    }

    /**
     * Up to {@code limit} code names closest to {@code question}'s words, for an answer that matched
     * nothing: a name within a few edits of a word, or one whose camel-case parts start with it.
     */
    public List<String> nearest(String question, int limit) {
        List<String> terms =
                queryTerms(question).stream().filter(t -> t.length() >= 3).toList();
        if (terms.isEmpty()) return List.of();
        record Near(int distance, int length, String label, String id) {}
        Map<String, Near> best = new HashMap<>();
        for (GraphNode n : graph.nodes()) {
            if (!n.fileType().equals("code") || n.sourceFile().isEmpty() || !scope.contains(n)) continue;
            String label = stripParens(n.normLabel());
            if (label.startsWith(".")) label = label.substring(1);
            if (label.isEmpty()) continue;
            int d = Integer.MAX_VALUE;
            for (String t : terms) {
                int edits = edits(t, label, Math.max(1, t.length() / 3));
                if (edits >= 0) d = Math.min(d, edits);
                for (String part : GraphText.searchTokens(splitCamel(n.label()))) {
                    if (part.startsWith(t) || t.startsWith(part) && part.length() >= 4) d = Math.min(d, 1);
                }
            }
            if (d == Integer.MAX_VALUE) continue;
            Near near = new Near(d, label.length(), n.label(), n.id());
            best.merge(label, near, (a, b) -> a.id().compareTo(b.id()) <= 0 ? a : b);
        }
        return best.values().stream()
                .sorted(Comparator.comparingInt(Near::distance)
                        .thenComparingInt(Near::length)
                        .thenComparing(Near::id))
                .limit(limit)
                .map(Near::id)
                .toList();
    }

    /**
     * A word folded to the form its inflections share, enough to meet a name's words: plurals
     * ({@code errors}, {@code passwords}, {@code queries}) and the past tense ({@code booked}). Not a
     * stemmer; it never shortens a word below four letters.
     */
    static String stem(String word) {
        int n = word.length();
        if (n > 4 && word.endsWith("ies")) return word.substring(0, n - 3) + "y";
        if (n > 4 && (word.endsWith("sses") || word.endsWith("xes") || word.endsWith("ches") || word.endsWith("shes")))
            return word.substring(0, n - 2);
        if (n > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us"))
            return word.substring(0, n - 1);
        if (n > 5 && word.endsWith("ed")) return word.substring(0, n - 2);
        return word;
    }

    /** A node in a test source root ({@code src/test}, {@code src/integration}, {@code src/e2e}, …). */
    private static boolean isTest(GraphNode n) {
        String f = n.sourceFile();
        return f.contains("/src/test/")
                || f.contains("/src/integration/")
                || f.contains("/src/e2e/")
                || f.contains("/src/testFixtures/")
                || f.endsWith(".spec.ts")
                || f.startsWith("src/test/");
    }

    /**
     * Two folded words read as one: equal, or sharing a prefix of at least six letters that is three
     * quarters of the shorter ({@code available} and {@code availability}, {@code cancel} and
     * {@code cancellation}).
     */
    static boolean sameWord(String a, String b) {
        if (a.equals(b)) return true;
        int shorter = Math.min(a.length(), b.length());
        int common = 0;
        while (common < shorter && a.charAt(common) == b.charAt(common)) common++;
        return common >= 6 && common * 4 >= shorter * 3;
    }

    /** {@code OrderService} → {@code Order Service}. */
    private static String splitCamel(String label) {
        return label.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
    }

    /** Levenshtein distance between {@code a} and {@code b}, or {@code -1} once it exceeds {@code max}. */
    static int edits(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) return -1;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > max) return -1;
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()] <= max ? prev[b.length()] : -1;
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
