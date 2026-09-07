// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.validate;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The test-tag tier table and its two invariants. Suites are scope, tags are cost: the root
 * manifest's {@code [test]} is the fast tier and every {@code [profiles.*]} but {@code ci} (the
 * fast tier under another name, auto-selected on CI) is a tier of its own. Every subset of the tag
 * vocabulary must be run by exactly one tier — exhaustive over {@code 2^|vocabulary|}, refused past
 * sixteen tags rather than enumerated — and every {@code @Tag} a compiled test carries must be a
 * tag some tier owns, or it runs in the fast tier by default, which is how a typo becomes a slow
 * {@code jk test}. Reports under the reserved code {@code tiers}.
 */
public final class TierPartition {

    public static final String CODE = "tiers";
    static final int MAX_VOCABULARY = 16;
    static final String TAG = "org.junit.jupiter.api.Tag";
    static final String FAST_TIER = "jk test";

    /** Tags that are deliberately not tier routing, with the reason each is allowed. */
    public static final Map<String, String> FIXTURE_TAGS = Map.of(
            "[slow]", "LauncherPathTest fixture: a tag with regex metacharacters in it",
            "brackets", "LauncherPathTest fixture: the sibling plain tag it is compared against");

    private TierPartition() {}

    /** JUnit's own tag semantics: runs when the include set is empty or intersects, and exclude does not. */
    public record Tier(String name, Set<String> include, Set<String> exclude) {
        public boolean runs(Set<String> tags) {
            boolean included = include.isEmpty();
            for (String t : tags) {
                if (include.contains(t)) included = true;
                if (exclude.contains(t)) return false;
            }
            return included;
        }
    }

    /** The tiers a manifest declares and the vocabulary they own. */
    public record Table(List<Tier> tiers, List<String> vocabulary) {
        public static final Table EMPTY = new Table(List.of(), List.of());

        public List<String> tiersFor(Set<String> tags) {
            List<String> out = new ArrayList<>();
            for (Tier t : tiers) if (t.runs(tags)) out.add(t.name());
            return out;
        }
    }

    /** The tier table the root manifest declares; {@link Table#EMPTY} when there is no manifest or no {@code [test]}. */
    public static Table table(Path root) {
        Path manifest = root.resolve(ManifestPaths.MANIFEST);
        if (!Files.isRegularFile(manifest)) return Table.EMPTY;
        List<Tier> tiers = new ArrayList<>();
        Set<String> vocabulary = new TreeSet<>();
        try {
            JkBuildParser.TestTomlTags fast = JkBuildParser.parseTestTags(manifest);
            tiers.add(new Tier(
                    FAST_TIER, new LinkedHashSet<>(fast.includeTags()), new LinkedHashSet<>(fast.excludeTags())));
            JkBuild build = JkBuildParser.parse(manifest);
            for (Map.Entry<String, Profile> e : new TreeMap<>(build.profiles().byName()).entrySet()) {
                if (e.getKey().equals("ci")) {
                    vocabulary.addAll(e.getValue().includeTags());
                    vocabulary.addAll(e.getValue().excludeTags());
                    continue;
                }
                tiers.add(new Tier(
                        "jk test --profile " + e.getKey(),
                        new LinkedHashSet<>(e.getValue().includeTags()),
                        new LinkedHashSet<>(e.getValue().excludeTags())));
            }
        } catch (IOException | RuntimeException e) {
            // a manifest that does not parse has its own diagnostics upstream of every guard lane
            return Table.EMPTY;
        }
        for (Tier t : tiers) {
            vocabulary.addAll(t.include());
            vocabulary.addAll(t.exclude());
        }
        return new Table(tiers, List.copyOf(vocabulary));
    }

    /** Arm 1, totality: every tag combination is run by exactly one tier. */
    public static List<Fault> partitionFaults(Table table) {
        List<Fault> faults = new ArrayList<>();
        if (table.vocabulary().isEmpty()) return faults;
        if (table.vocabulary().size() > MAX_VOCABULARY) {
            faults.add(new Fault(
                    CODE,
                    "the tag vocabulary has " + table.vocabulary().size()
                            + " tags; exhaustive partition checking stops at " + MAX_VOCABULARY,
                    "fewer tags — a tier per cost, not a tag per test"));
            return faults;
        }
        List<String> bad = new ArrayList<>();
        List<String> vocab = table.vocabulary();
        for (int mask = 0; mask < (1 << vocab.size()); mask++) {
            Set<String> tags = new TreeSet<>();
            for (int i = 0; i < vocab.size(); i++) if ((mask >> i & 1) == 1) tags.add(vocab.get(i));
            List<String> run = table.tiersFor(tags);
            if (run.size() != 1) {
                bad.add("@Tag" + (tags.isEmpty() ? "[(untagged)]" : tags.toString()) + " — "
                        + (run.isEmpty() ? "no tier runs it" : "run by " + run.size() + " tiers: " + run));
            }
        }
        if (!bad.isEmpty()) {
            faults.add(
                    new Fault(
                            CODE,
                            "the tier table in jk.toml does not partition its own vocabulary " + vocab + ":\n    "
                                    + String.join("\n    ", bad),
                            "every tag combination run by exactly one tier — a tag in [test] exclude-tags needs a profile that includes it, or the exclusion is a hole; two tiers running one combination charges a test to two budgets"));
        }
        return faults;
    }

    /** Arms 2 and 3 over one module's compiled tests: each tagged element runs in exactly one tier, and every tag is owned. */
    public static List<Fault> tagFaults(Table table, FactsIndex tests, String module) {
        List<Fault> faults = new ArrayList<>();
        if (table.vocabulary().isEmpty()) return faults;
        List<String> orphans = new ArrayList<>();
        Set<String> unowned = new TreeSet<>();
        for (ClassFacts c : tests.classList()) {
            Set<String> classTags = tags(c.annotations());
            String where = (module.isEmpty() ? "" : module + ": ") + c.binaryName();
            if (!classTags.isEmpty()) element(table, classTags, where, orphans, unowned);
            for (MethodFacts m : c.methods()) {
                Set<String> own = tags(m.annotations());
                if (own.isEmpty()) continue;
                Set<String> all = new TreeSet<>(classTags);
                all.addAll(own);
                element(table, all, where + "#" + m.name(), orphans, unowned);
            }
        }
        if (!orphans.isEmpty()) {
            faults.add(
                    new Fault(
                            CODE,
                            "these @Tag elements are not run by exactly one tier:\n    "
                                    + String.join("\n    ", orphans),
                            "a tag excluded from the fast tier and included by no profile is a test that never executes and never goes red — give the tag a profile in the root jk.toml, or stop excluding it"));
        }
        if (!unowned.isEmpty()) {
            faults.add(
                    new Fault(
                            CODE,
                            "these @Tag values are in neither the tier vocabulary " + table.vocabulary()
                                    + " nor the fixture allowlist: " + unowned,
                            "a tag no tier owns runs in the fast tier by default, which is how a typo becomes a slow `jk test` — spell it as a vocabulary tag or give it a profile of its own"));
        }
        return faults;
    }

    private static void element(
            Table table, Set<String> tags, String where, List<String> orphans, Set<String> unowned) {
        Set<String> routing = new TreeSet<>();
        for (String t : tags) {
            if (FIXTURE_TAGS.containsKey(t)) continue;
            routing.add(t);
            if (!table.vocabulary().contains(t)) unowned.add(t);
        }
        if (routing.isEmpty()) return;
        List<String> run = table.tiersFor(routing);
        if (run.size() != 1) {
            orphans.add(where + " @Tag" + routing + " — " + (run.isEmpty() ? "no tier runs it" : "run by " + run));
        }
    }

    static Set<String> tags(List<AnnotationFacts> annotations) {
        Set<String> out = new LinkedHashSet<>();
        for (AnnotationFacts a : annotations) if (a.typeName().equals(TAG)) out.addAll(a.value());
        return out;
    }
}
