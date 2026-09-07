// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.explain;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.BaselineFile;
import cc.jumpkick.guard.eval.GuardSuites;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadError;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.guard.schema.SchemaText;
import cc.jumpkick.guard.validate.EngineValidations;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk guard explain}: one rule's card, the catalog, or a kind's schema. Read-only — rules
 * and baseline from their files, the last outcome from {@link RuleSummaries}; the sha256 of both
 * files closes the card so an agent can tell whether what it read is what the build enforces.
 */
public final class GuardExplain {

    private GuardExplain() {}

    /** The text and the JSON form of the same answer, or a printable error. */
    public record Result(@Nullable String error, String text, String json) {
        static Result error(String message) {
            return new Result(message, "", "");
        }
    }

    /** One rule as the card and the catalog see it. */
    public record Card(
            String id,
            String kind,
            String scope,
            String why,
            String instead,
            String source,
            String population,
            int baselineEntries,
            String lastOutcome,
            String layer) {}

    public static Result explain(Path root, GuardsConfig config, @Nullable String ruleId) throws IOException {
        Path rulesFile = GuardsPresence.rulesFile(root);
        if (!Files.isRegularFile(rulesFile)
                && !config.declared()
                && GuardSuites.declaredAcrossWorkspace(root).isEmpty()) {
            return Result.error("no guards here: no " + GuardsPresence.RULES_FILE + " in " + root
                    + " (jk guard explain --schema <kind> shows how to write the first rule)");
        }
        LoadResult load = GuardRules.load(root, config);
        if (load.hasErrors()) {
            StringBuilder sb = new StringBuilder(GuardsPresence.RULES_FILE + " does not load:\n");
            for (LoadError e : load.problems())
                sb.append("  ").append(e.render()).append('\n');
            return Result.error(sb.toString().stripTrailing());
        }
        Baseline baseline = BaselineFile.read(GuardsPresence.baselineFile(root));
        Map<String, List<RuleSummaries.Summary>> summaries = new TreeMap<>();
        for (RuleSummaries.Summary s : RuleSummaries.read(root)) {
            summaries.computeIfAbsent(s.code(), k -> new ArrayList<>()).add(s);
        }
        List<Card> cards = new ArrayList<>();
        for (Rule r : new TreeMap<>(load.rules().rules()).values()) {
            cards.add(card(r, baseline, summaries.getOrDefault(r.id(), List.of())));
        }
        // Guard tests the compiled suites declare read as cards too: kind `test`, source = the method.
        for (GuardSuites.Located g : GuardSuites.declaredAcrossWorkspace(root).values()) {
            if (load.rules().rules().containsKey(g.declared().id())) continue;
            Rule r = GuardSuites.rule(g.declared(), root, g.module());
            cards.add(card(r, baseline, summaries.getOrDefault(r.id(), List.of())));
        }
        String rulesSha = Files.isRegularFile(rulesFile) ? Hashing.sha256Hex(rulesFile) : "absent";
        Path baselineFile = GuardsPresence.baselineFile(root);
        String baselineSha = Files.isRegularFile(baselineFile) ? Hashing.sha256Hex(baselineFile) : "absent";
        if (ruleId != null) {
            for (EngineValidations.Info v : EngineValidations.ALL) {
                if (v.code().equals(ruleId)) {
                    return new Result(null, renderValidation(v, rulesSha, baselineSha), validationJson(v));
                }
            }
            Card hit = null;
            for (Card c : cards) if (c.id().equals(ruleId)) hit = c;
            if (hit == null) {
                List<String> ids = new ArrayList<>();
                for (Card c : cards) ids.add(c.id());
                return Result.error("no rule `" + ruleId + "` in " + GuardsPresence.RULES_FILE + "; nearest: "
                        + String.join(", ", nearest(ruleId, ids)));
            }
            return new Result(null, renderCard(hit, rulesSha, baselineSha), json(List.of(hit), rulesSha, baselineSha));
        }
        return new Result(null, renderCatalog(cards, rulesSha, baselineSha), json(cards, rulesSha, baselineSha));
    }

    /** {@code --schema <kind>}: the kind's keys and one example; {@code guard-test}: the skeleton. */
    public static Result schema(String what) {
        if (what.equals("guard-test")) return new Result(null, GUARD_TEST_SKELETON, Jsonl.quote(GUARD_TEST_SKELETON));
        var kind = Kind.byId(what);
        if (kind.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (Kind k : Kind.values()) ids.add(k.id());
            return Result.error("unknown kind `" + what + "`; kinds are " + String.join(", ", ids) + ", guard-test");
        }
        String text = SchemaText.render(kind.get());
        return new Result(null, text, "{\"kind\":" + Jsonl.quote(what) + ",\"schema\":" + Jsonl.quote(text) + "}");
    }

    static Card card(Rule r, Baseline baseline, List<RuleSummaries.Summary> summaries) {
        String population = "";
        String last = "never evaluated";
        if (!summaries.isEmpty()) {
            int fresh = 0;
            int baselined = 0;
            Map<String, Long> pop = new TreeMap<>();
            String worst = "clean";
            long newest = 0;
            for (RuleSummaries.Summary s : summaries) {
                fresh += s.fresh();
                baselined += s.baselined();
                newest = Math.max(newest, s.ts());
                for (String kv : s.population().split(" ")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) pop.merge(kv.substring(0, eq), Long.parseLong(kv.substring(eq + 1)), Long::sum);
                }
                if (!s.outcome().equals("clean") && !s.outcome().equals("violations")) worst = s.outcome();
            }
            population = RuleSummaries.population(pop);
            if (fresh > 0) last = "red · " + fresh + (fresh == 1 ? " new site" : " new sites");
            else if (!worst.equals("clean")) last = worst;
            else if (baselined > 0) last = "clean · " + baselined + " baselined";
            else last = "clean";
            if (summaries.size() > 1) last += " (" + summaries.size() + " lanes)";
        }
        return new Card(
                r.id(),
                r.kind().id(),
                r.scope().isEmpty() ? "every module" : String.join(", ", r.scope()),
                r.why(),
                r.instead() == null ? "" : r.instead(),
                r.source().render(),
                population,
                baseline.of(r.id()).entries().size(),
                last,
                r.kind() == Kind.TEST ? "guard tests (src/guard)" : r.source().layerLabel());
    }

    static String renderCard(Card c, String rulesSha, String baselineSha) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.id()).append("  (").append(c.kind()).append(")\n");
        field(sb, "Why", c.why());
        if (!c.instead().isEmpty()) field(sb, "Instead", c.instead());
        field(sb, "Scope", c.scope());
        field(sb, "Source", c.source());
        field(sb, "Population", c.population().isEmpty() ? "—" : c.population());
        field(
                sb,
                "Baseline",
                c.baselineEntries() == 0
                        ? "no entries"
                        : c.baselineEntries() + (c.baselineEntries() == 1 ? " entry" : " entries"));
        field(sb, "Last", c.lastOutcome());
        sb.append('\n');
        shas(sb, rulesSha, baselineSha);
        return sb.toString();
    }

    static String renderCatalog(List<Card> cards, String rulesSha, String baselineSha) {
        StringBuilder sb = new StringBuilder();
        if (cards.isEmpty()) {
            sb.append("no rules in ").append(GuardsPresence.RULES_FILE).append('\n');
        } else {
            int idW = "id".length();
            int kindW = "kind".length();
            int lastW = "last".length();
            for (Card c : cards) {
                idW = Math.max(idW, c.id().length());
                kindW = Math.max(kindW, c.kind().length());
                lastW = Math.max(lastW, c.lastOutcome().length());
            }
            sb.append(pad("id", idW))
                    .append("  ")
                    .append(pad("kind", kindW))
                    .append("  ")
                    .append(pad("last", lastW))
                    .append("  ")
                    .append("base")
                    .append("  ")
                    .append("why\n");
            // More than one source layer: a header line per layer, root first, then packs, then members.
            Set<String> layers = new LinkedHashSet<>();
            for (Card c : cards) layers.add(c.layer());
            boolean grouped = layers.size() > 1;
            List<Card> ordered = new ArrayList<>(cards);
            if (grouped)
                ordered.sort(Comparator.comparingInt((Card c) -> layerRank(c.layer()))
                        .thenComparing(Card::id));
            String currentLayer = null;
            for (Card c : ordered) {
                if (grouped && !c.layer().equals(currentLayer)) {
                    currentLayer = c.layer();
                    sb.append("── ").append(currentLayer).append('\n');
                }
                sb.append(pad(c.id(), idW))
                        .append("  ")
                        .append(pad(c.kind(), kindW))
                        .append("  ")
                        .append(pad(c.lastOutcome(), lastW))
                        .append("  ")
                        .append(pad(Integer.toString(c.baselineEntries()), 4))
                        .append("  ")
                        .append(c.why())
                        .append('\n');
            }
            sb.append('\n')
                    .append(cards.size())
                    .append(cards.size() == 1 ? " rule" : " rules")
                    .append(" · jk guard explain <id> for the card · --schema <kind> to write one\n\n");
        }
        sb.append("engine validations (no table, no baseline; they ride the lanes):\n");
        for (EngineValidations.Info v : EngineValidations.ALL) {
            sb.append("  ")
                    .append(pad(v.code(), 14))
                    .append(pad(v.lanes(), 15))
                    .append(v.what())
                    .append('\n');
        }
        sb.append('\n');
        shas(sb, rulesSha, baselineSha);
        return sb.toString();
    }

    /** Root file, packs, members, guard tests: the order the layers stack in. */
    static int layerRank(String layer) {
        if (layer.equals(GuardsPresence.RULES_FILE)) return 0;
        if (layer.startsWith("pack ")) return 1;
        if (layer.startsWith("guard tests")) return 3;
        return 2;
    }

    static String renderValidation(EngineValidations.Info v, String rulesSha, String baselineSha) {
        StringBuilder sb = new StringBuilder("GUARD ").append(v.code()).append("  engine validation\n");
        field(sb, "lanes", v.lanes());
        field(sb, "checks", v.what());
        field(sb, "why", v.why());
        field(sb, "source", "the engine — not a rule; nothing to edit in " + GuardsPresence.RULES_FILE);
        sb.append('\n');
        shas(sb, rulesSha, baselineSha);
        return sb.toString();
    }

    static String validationJson(EngineValidations.Info v) {
        return "{\"validations\":[{\"code\":" + Jsonl.quote(v.code()) + ",\"lanes\":" + Jsonl.quote(v.lanes())
                + ",\"checks\":" + Jsonl.quote(v.what()) + ",\"why\":" + Jsonl.quote(v.why()) + "}]}";
    }

    private static void shas(StringBuilder sb, String rulesSha, String baselineSha) {
        sb.append(GuardsPresence.RULES_FILE)
                .append("  sha256 ")
                .append(rulesSha)
                .append('\n');
        sb.append(GuardsPresence.BASELINE_FILE)
                .append("  sha256 ")
                .append(baselineSha)
                .append('\n');
    }

    private static void field(StringBuilder sb, String label, String value) {
        sb.append("  ").append(pad(label + ":", 12)).append(value).append('\n');
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }

    static String json(List<Card> cards, String rulesSha, String baselineSha) {
        StringBuilder sb = new StringBuilder("{\"rules\":[");
        boolean first = true;
        for (Card c : cards) {
            if (!first) sb.append(',');
            first = false;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("kind", c.kind());
            m.put("scope", c.scope());
            m.put("why", c.why());
            m.put("instead", c.instead());
            m.put("source", c.source());
            m.put("layer", c.layer());
            m.put("population", c.population());
            m.put("baselineEntries", c.baselineEntries());
            m.put("lastOutcome", c.lastOutcome());
            sb.append('{');
            boolean f2 = true;
            for (var e : m.entrySet()) {
                if (!f2) sb.append(',');
                f2 = false;
                sb.append(Jsonl.quote(e.getKey())).append(':');
                sb.append(
                        e.getValue() instanceof Integer i
                                ? Integer.toString(i)
                                : Jsonl.quote(String.valueOf(e.getValue())));
            }
            sb.append('}');
        }
        sb.append("],\"rulesSha\":").append(Jsonl.quote(rulesSha));
        sb.append(",\"baselineSha\":").append(Jsonl.quote(baselineSha)).append('}');
        return sb.toString();
    }

    /** Ids sharing the longest prefix with {@code q} first, then the rest, at most five. */
    static List<String> nearest(String q, List<String> ids) {
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort((a, b) -> {
            int d = Integer.compare(commonPrefix(q, b), commonPrefix(q, a));
            return d != 0 ? d : a.compareTo(b);
        });
        return sorted.subList(0, Math.min(5, sorted.size()));
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        if (i == 0 && b.contains(q(a))) return 1;
        return i;
    }

    private static String q(String a) {
        return a.length() > 3 ? a.substring(0, 3) : a;
    }

    static final String GUARD_TEST_SKELETON = """
            // src/guard/java/<pkg>/HouseRules.java — compiled as the `guard` suite, run by `jk guard test`
            @GuardSuite(scope = Scope.MODULE)          // MODULE: lane guard:<module>; WORKSPACE: guard-workspace
            final class HouseRules {

                @Guard(id = "<rule-id>",                 // the diagnostic code; shares the namespace with TOML ids
                       why = "<one sentence: the invariant this protects>",
                       instead = "<the sanctioned alternative an agent applies>")
                @Fixture("guard-fixtures/<rule-id>")     // must bite: a tree the rule fails on
                void rule(Facts facts, Violations v) {
                    for (CallSite s : facts.calls(Sig.of("java.lang.String#replace(**)"))) {
                        v.add(s, "<what is wrong at this site>");
                    }
                }
            }

            injectable: Facts (classes(), calls(Sig), fieldRefs(), annotations(on), constants(owner),
                        packageEdges(), testClasses(), classDirs())
                        Model (modules(), deps(module, scope), lock(), tiers(), toolchain())
                        Text  (files(glob), blanked(path, mode), literals(path), lines(path)) — WORKSPACE scope
                        Output (poms(), jars(), coverage())
                        Violations (add(Site, detail); fingerprints, instead and why are the engine's)
            exemptions: @Allow(in = "<module or class glob>", reason = "…") — never a suppression comment
            """;
}
