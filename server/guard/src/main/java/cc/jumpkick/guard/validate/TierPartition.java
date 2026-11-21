// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.validate;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.guard.facts.TestClassFacts;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.DeclaredSuite;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The test-tag tier table and its two invariants. Tags are cost: every root
 * {@code [test.suites.<name>]} that claims tests by tag is a tier of its own ({@code jk test -s
 * <name>}, the built-in {@code integration} suite among them), and the default suite, which keeps
 * what no suite claims, is the fast tier ({@code jk test}). Every subset of the tag vocabulary must
 * be run by exactly one tier: no two suites may run one test unless a suite's {@code exclude-tags}
 * settles it, and no claimed test may be left to none — exhaustive over {@code 2^|vocabulary|}, refused past
 * sixteen tags rather than enumerated — and every {@code @Tag} a compiled test carries must be a
 * tag some tier owns, or it runs in the fast tier by default, which is how a typo becomes a slow
 * {@code jk test}. Reports under the reserved code {@code tiers}.
 */
public final class TierPartition {

    public static final String CODE = "tiers";
    static final int MAX_VOCABULARY = 16;
    static final String FAST_TIER = "jk test";
    static final String SUITE_TIER = "jk test -s ";

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

    /**
     * The tiers a manifest declares, the vocabulary they own, and the tags that are scheduling
     * rather than routing.
     *
     * @param scheduling the root's {@code [test] serial-tags}. A tag there says <em>how</em> a
     *     class is run — alone, off the sharded pool — not <em>which tier</em> runs it, so it is
     *     exempt from the ownership arm the way {@link #FIXTURE_TAGS} is. The routing arms still
     *     see the class's other tags; a class whose only tag is a scheduling one routes as an
     *     untagged test does, into the fast tier, which is what it is — a fast-tier test that
     *     happens to need the pool to itself.
     */
    public record Table(List<Tier> tiers, List<String> vocabulary, List<String> scheduling) {
        public static final Table EMPTY = new Table(List.of(), List.of(), List.of());

        public Table(List<Tier> tiers, List<String> vocabulary) {
            this(tiers, vocabulary, List.of());
        }

        public List<String> tiersFor(Set<String> tags) {
            List<String> out = new ArrayList<>();
            for (Tier t : tiers) if (t.runs(tags)) out.add(t.name());
            return out;
        }
    }

    /**
     * The tier table the root manifest's suites declare; {@link Table#EMPTY} when there is no
     * manifest or no table claims tests by tag (a project that routes no tag has no tiers to check).
     */
    public static Table table(Path root) {
        Path manifest = ManifestPaths.manifestIn(root);
        if (!Files.isRegularFile(manifest)) return Table.EMPTY;
        List<Tier> tiers = new ArrayList<>();
        Set<String> vocabulary = new TreeSet<>();
        Set<String> scheduling = new TreeSet<>();
        try {
            JkBuild build = JkBuildParser.parse(manifest);
            if (build.build().testSuites().values().stream()
                    .allMatch(s -> s.tags().isEmpty())) return Table.EMPTY;
            scheduling.addAll(build.build().testSerialTags());
            Set<String> claimed = new LinkedHashSet<>();
            for (Map.Entry<String, DeclaredSuite> e : build.build().runSuites().entrySet()) {
                DeclaredSuite suite = e.getValue();
                if (suite.tags().isEmpty()) continue;
                claimed.addAll(suite.tags());
                tiers.add(new Tier(
                        SUITE_TIER + e.getKey(),
                        new LinkedHashSet<>(suite.tags()),
                        new LinkedHashSet<>(suite.excludeTags())));
            }
            tiers.addFirst(new Tier(FAST_TIER, Set.of(), claimed));
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (IOException | RuntimeException e) {
            // a manifest that does not parse has its own diagnostics upstream of every guard lane
            return Table.EMPTY;
        }
        for (Tier t : tiers) {
            vocabulary.addAll(t.include());
            vocabulary.addAll(t.exclude());
        }
        return new Table(tiers, List.copyOf(vocabulary), List.copyOf(scheduling));
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
                            "jk.toml's test suites do not run each tag combination exactly once " + vocab + ":\n    "
                                    + String.join("\n    ", bad),
                            "each tag combination runs in exactly one suite; two suites claiming one tag need an exclude-tags that settles it"));
        }
        return faults;
    }

    /**
     * Arms 2 and 3 over one module's compiled tests: each tagged element runs in exactly one tier, and
     * every tag is owned. Tags are read as the engine reads them, by {@code testClasses}: a class's
     * own and its supertypes', each directly or through a composed annotation.
     */
    public static List<Fault> tagFaults(Table table, FactsIndex tests, TestClassFacts testClasses, String module) {
        List<Fault> faults = new ArrayList<>();
        if (table.vocabulary().isEmpty()) return faults;
        List<String> orphans = new ArrayList<>();
        Set<String> unowned = new TreeSet<>();
        for (ClassFacts c : tests.classList()) {
            Set<String> classTags = testClasses.classTags(c);
            String where = (module.isEmpty() ? "" : module + ": ") + c.binaryName();
            if (!classTags.isEmpty()) element(table, classTags, where, orphans, unowned);
            for (MethodFacts m : c.methods()) {
                Set<String> own = testClasses.methodTags(m);
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
                            "a test every claiming suite excludes never runs; give its tag a [test.suites.<name>] in the root jk.toml, or stop excluding it"));
        }
        if (!unowned.isEmpty()) {
            faults.add(
                    new Fault(
                            CODE,
                            "these @Tag values are in neither the tier vocabulary " + table.vocabulary()
                                    + " nor the fixture allowlist: " + unowned,
                            "an unknown tag runs with the default tests; fix its spelling or give it a [test.suites.<name>] table"));
        }
        return faults;
    }

    private static void element(
            Table table, Set<String> tags, String where, List<String> orphans, Set<String> unowned) {
        Set<String> routing = new TreeSet<>();
        for (String t : tags) {
            if (FIXTURE_TAGS.containsKey(t) || table.scheduling().contains(t)) continue;
            routing.add(t);
            if (!table.vocabulary().contains(t)) unowned.add(t);
        }
        if (routing.isEmpty()) return;
        List<String> run = table.tiersFor(routing);
        if (run.size() != 1) {
            orphans.add(where + " @Tag" + routing + " — " + (run.isEmpty() ? "no tier runs it" : "run by " + run));
        }
    }
}
