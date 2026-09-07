// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.explain;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.Entry;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.GuardsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SarifWriterTest {

    private static final String RULES = """
            [guards.one-digest-surface]
            kind       = "forbid"
            signatures = ["java.security.MessageDigest#getInstance(**)"]
            owner      = "cc.jumpkick.host.Hashing"
            instead    = "Hashing.newSha256()"
            why        = "one digest surface"
            [guards.file-size]
            kind    = "metric"
            measure = "lines"
            cap     = 800
            why     = "a file nobody can read whole"
            [guards.no-todo]
            kind    = "text"
            pattern = "TODO"
            hit     = "TODO"
            instead = "file a ticket"
            why     = "todo rots"
            """;

    /** What two lanes left under target/jk-guards: a new site, a baselined site, a metric, a scanner failure and a blind rule. */
    private static void laneOutput(Path root) throws Exception {
        Path dir = RuleSummaries.dir(root);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("guard_mod.jsonl"), """
                {"code":"one-digest-surface","kind":"forbid","baseline":"new","file":"mod/src/main/java/a/A.java","line":12,"at":"a.A#f() -> MessageDigest#getInstance","message":"MessageDigest.getInstance called outside Hashing","instead":"Hashing.newSha256()","why":"one digest surface","source":"jk-guards.toml:1"}
                {"code":"one-digest-surface","kind":"forbid","baseline":"baselined","file":"mod/src/main/java/a/B.java","line":3,"at":"a.B#g() -> MessageDigest#getInstance","message":"MessageDigest.getInstance called outside Hashing","instead":"Hashing.newSha256()","why":"one digest surface","source":"jk-guards.toml:1"}
                """);
        Files.writeString(dir.resolve("guard-tree.jsonl"), """
                {"code":"file-size","kind":"metric","baseline":"baselined","file":"mod/src/main/java/a/Big.java","line":0,"at":"mod/src/main/java/a/Big.java","message":"lines = 950 (cap 800)","instead":"","why":"a file nobody can read whole","source":"jk-guards.toml:7"}
                """);
        Files.writeString(dir.resolve("guard_mod.rules.jsonl"), """
                {"code":"one-digest-surface","lane":"guard:mod","outcome":"violations","population":"classes=4","fresh":1,"baselined":1,"bite":true,"note":"","ts":10}
                """);
        Files.writeString(dir.resolve("guard-tree.rules.jsonl"), """
                {"code":"file-size","lane":"guard-tree","outcome":"violations","population":"files=9","fresh":0,"baselined":1,"bite":true,"note":"","ts":11}
                {"code":"no-todo","lane":"guard-tree","outcome":"scanner-failed","population":"","fresh":0,"baselined":0,"bite":false,"note":"regex deadline of 2000 ms exceeded on mod/src/main/java/a/Huge.java","ts":11}
                {"code":"gt-empty","lane":"guard-tree","outcome":"blind","population":"files=0","fresh":0,"baselined":0,"bite":false,"note":"the rule examined nothing","ts":11}
                """);
    }

    @Test
    void the_document_validates_and_says_what_the_lanes_found(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), RULES);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        laneOutput(root);
        Baseline baseline = Baseline.EMPTY
                .with(
                        "one-digest-surface",
                        RuleBaseline.of(
                                Map.of("classes", 4L),
                                List.of(new Entry.Site(
                                        "a.B#g() -> MessageDigest#getInstance",
                                        "legacy digest, retiring with the old store"))))
                .with(
                        "file-size",
                        RuleBaseline.of(
                                Map.of("files", 9L),
                                List.of(new Entry.Metric(
                                        "mod/src/main/java/a/Big.java", 950, "split when the parser moves"))));
        SarifWriter.write(root, load.rules(), baseline);

        Path sarif = root.resolve("target/jk-guards.sarif");
        assertThat(sarif).exists();
        Object doc = MiniJson.parse(Files.readString(sarif));
        assertThat(SarifSchema.vendored().validate(doc))
                .as("SARIF 2.1.0 schema")
                .isEmpty();

        Object run = MiniJson.list(doc, "runs").get(0);
        Object invocation = MiniJson.list(run, "invocations").get(0);
        assertThat(MiniJson.get(invocation, "executionSuccessful"))
                .as("a scanner-failed rule")
                .isEqualTo(Boolean.FALSE);
        List<?> notifications = MiniJson.list(invocation, "toolExecutionNotifications");
        assertThat(notifications).hasSize(1);
        assertThat(MiniJson.str(MiniJson.get(notifications.get(0), "message"), "text"))
                .contains("no-todo")
                .contains("regex deadline");

        List<?> results = MiniJson.list(run, "results");
        assertThat(results).hasSize(4); // new site, baselined site, baselined metric, blind rule
        Object fresh = pick(results, r -> "new".equals(MiniJson.str(r, "baselineState")));
        assertThat(MiniJson.str(fresh, "ruleId")).isEqualTo("one-digest-surface");
        assertThat(MiniJson.get(fresh, "suppressions")).isNull();
        assertThat(MiniJson.str(MiniJson.get(fresh, "partialFingerprints"), SarifWriter.FINGERPRINT_KEY))
                .isEqualTo("a.A#f() -> MessageDigest#getInstance");
        Object location = MiniJson.list(fresh, "locations").get(0);
        Object artifact = MiniJson.get(MiniJson.get(location, "physicalLocation"), "artifactLocation");
        assertThat(MiniJson.str(artifact, "uri")).isEqualTo("mod/src/main/java/a/A.java");
        assertThat(MiniJson.str(artifact, "uriBaseId")).isEqualTo("%SRCROOT%");
        Object baselined = pick(results, r -> "a.B#g() -> MessageDigest#getInstance"
                .equals(MiniJson.str(MiniJson.get(r, "partialFingerprints"), SarifWriter.FINGERPRINT_KEY)));
        assertThat(MiniJson.str(baselined, "baselineState")).isEqualTo("unchanged");
        Object suppression = MiniJson.list(baselined, "suppressions").get(0);
        assertThat(MiniJson.str(suppression, "kind")).isEqualTo("external");
        assertThat(MiniJson.str(suppression, "justification")).isEqualTo("legacy digest, retiring with the old store");
        Object metric = pick(results, r -> "file-size".equals(MiniJson.str(r, "ruleId")));
        assertThat(MiniJson.str(MiniJson.list(metric, "suppressions").get(0), "justification"))
                .isEqualTo("split when the parser moves");
        Object blind = pick(results, r -> "gt-empty".equals(MiniJson.str(r, "ruleId")));
        assertThat(MiniJson.str(blind, "kind")).isEqualTo("fail");
        assertThat(MiniJson.get(blind, "locations")).isNull();

        List<?> rules = MiniJson.list(MiniJson.get(MiniJson.get(run, "tool"), "driver"), "rules");
        assertThat(rules)
                .extracting(r -> MiniJson.str(r, "id"))
                .containsExactly("file-size", "no-todo", "one-digest-surface", "gt-empty");
        assertThat(MiniJson.str(
                        MiniJson.get(pick(rules, r -> "one-digest-surface".equals(MiniJson.str(r, "id"))), "help"),
                        "text"))
                .isEqualTo("Hashing.newSha256()");
        assertThat(MiniJson.get(MiniJson.get(run, "originalUriBaseIds"), "%SRCROOT%"))
                .isNotNull();

        List<String> jsonl = Files.readAllLines(root.resolve("target/jk-guards.jsonl"));
        assertThat(jsonl).hasSize(3);
        assertThat(jsonl).anySatisfy(l -> assertThat(MiniJson.str(MiniJson.parse(l), "code"))
                .isEqualTo("one-digest-surface"));
    }

    private static Object pick(List<?> results, Predicate<Object> p) {
        for (Object r : results) if (r != null && p.test(r)) return r;
        throw new AssertionError("no result matched in " + results);
    }

    @Test
    void the_subset_validator_rejects_what_the_schema_forbids(@TempDir Path root) throws Exception {
        SarifSchema schema = SarifSchema.vendored();
        Object bad = MiniJson.parse(
                "{\"version\":\"2.1.0\",\"runs\":[{\"tool\":{\"driver\":{\"name\":\"x\"}},\"results\":[{\"message\":{\"text\":\"m\"},\"baselineState\":\"sideways\",\"bogus\":1}]}]}");
        List<String> errors = schema.validate(bad);
        assertThat(errors)
                .anySatisfy(e -> assertThat(e).contains("baselineState").contains("sideways"))
                .anySatisfy(e -> assertThat(e).contains("unexpected property `bogus`"));
        Object empty = MiniJson.parse("{\"version\":\"2.1.0\",\"runs\":[]}");
        assertThat(schema.validate(empty)).isEmpty();
    }

    @Test
    void no_lane_output_writes_nothing(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), RULES);
        SarifWriter.write(root, GuardRules.load(root, GuardsConfig.ABSENT).rules(), Baseline.EMPTY);
        assertThat(root.resolve("target/jk-guards.sarif")).doesNotExist();
    }
}
