// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import cc.jumpkick.guard.api.Blank;
import cc.jumpkick.guard.api.Facts;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Model;
import cc.jumpkick.guard.api.Owner;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Sig;
import cc.jumpkick.guard.api.Skipped;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.Violations;
import cc.jumpkick.guard.facts.CallSite;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** The extension runs a suite as JUnit tests and writes one report line per guard. */
class GuardRuntimeTest {

    /** A suite the test launches: one guard fires, one is clean, one throws, one probes a missing owner. */
    @Disabled("run by the test below through the launcher, with the condition deactivated")
    @GuardSuite(scope = Scope.MODULE)
    static class Suite {
        @Guard(id = "no-replace", why = "escaping by hand", instead = "Jsonl.quote")
        void fires(Facts facts, Text text, Violations v) {
            for (var s : facts.calls(Sig.of("java.lang.String#replace(**)"))) {
                String body = text.blanked("src/main/java/a/Esc.java", Blank.COMMENTS);
                if (body.contains("\":")) v.add(s, "an escaper beside a key literal");
            }
            v.population(facts.classes().size());
        }

        @Guard(id = "tiers-ok", why = "w")
        void clean(Model model, Violations v) {
            if (model.tiers().running(Set.of("slow")).size() != 1)
                v.add(model.tiers(), "slow runs in " + model.tiers().running(Set.of("slow")));
        }

        @Guard(id = "boom", why = "w")
        void throwsUp(Facts facts, Violations v) {
            throw new IllegalStateException("kaboom");
        }

        @Guard(id = "needs-owner", why = "w")
        void owner(Facts facts, Violations v) {
            Owner.require(facts, "a.Missing");
        }

        @Guard(id = "no-tool", why = "w")
        void skips(Facts facts, Violations v) {
            throw new Skipped("shellcheck: not installed");
        }
    }

    private static ClassFacts cls(String internal, String source, List<MethodFacts> methods) {
        return new ClassFacts(
                internal, 1, "java/lang/Object", List.of(), source, List.of(), List.of(), methods, Set.of());
    }

    @Test
    void a_suite_run_through_junit_writes_one_line_per_guard(@TempDir Path root) throws Exception {
        // facts: a.Esc#write calls String.replace with a quote literal nearby
        var call = new CallSite("java/lang/String", "replace", "(CC)Ljava/lang/String;", 7, "\"", 1, List.of("\""));
        MethodFacts write = new MethodFacts("write", "()V", 1, List.of(), List.of(), List.of(call), List.of(), 0, 5);
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        ClassFacts esc = cls("a/Esc", "Esc.java", List.of(write));
        m.put(esc.name(), esc);
        Path idx = root.resolve("main-guard.idx");
        FactsFormat.write(idx, new FactsIndex(m, Map.of(), "d1"));
        Path src = Files.createDirectories(root.resolve("src/main/java/a"));
        Files.writeString(
                src.resolve("Esc.java"),
                "package a;\nclass Esc { String write() { return \"{\\\"k\\\":\" + x.replace('\"', '\\''); } // \": in a comment\n}\n");
        Path model = root.resolve("model.json");
        Files.writeString(model, """
                {"modules":["","lib"],"deps":{"lib":{"dependencies":[{"coordinate":"org.x:y","version":"^1","workspace":false}]}},
                 "lock":[{"coordinate":"org.x:y","version":"1.2","repository":"central","scopes":["main"]}],
                 "tiers":{"tiers":[{"name":"jk test","include":[],"exclude":["slow"]},{"name":"jk test --profile slow","include":["slow"],"exclude":[]}]},
                 "toolchain":{"java":{"":25},"kotlin":null,"repositories":["central"]}}
                """);
        Path report = root.resolve("target/guard/report.jsonl");
        GuardRuntime.install(new GuardConfig(
                report,
                root,
                "",
                List.of(idx),
                List.of(),
                model,
                List.of(root.resolve("src")),
                List.of(),
                List.of(),
                List.of(),
                null,
                false,
                null));

        Launcher launcher = LauncherFactory.create();
        LauncherDiscoveryRequest req = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(Suite.class))
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                .configurationParameter("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
                .build();
        SummaryGeneratingListener summary = new SummaryGeneratingListener();
        launcher.execute(req, summary);
        assertThat(summary.getSummary().getTestsFailedCount())
                .as("violations and throws never fail the JUnit run")
                .isZero();
        assertThat(summary.getSummary().getTestsSucceededCount()).isEqualTo(5);

        List<String> lines = Files.readAllLines(report);
        assertThat(lines).hasSize(5);
        Map<String, Object> byId = new LinkedHashMap<>();
        for (String l : lines) byId.put(MiniJson.str(MiniJson.parse(l), "id"), MiniJson.parse(l));
        Object fires = byId.get("no-replace");
        assertThat(MiniJson.str(fires, "outcome")).isEqualTo("ok");
        assertThat(MiniJson.str(fires, "instead")).isEqualTo("Jsonl.quote");
        assertThat(MiniJson.str(fires, "source")).isEqualTo(Suite.class.getName() + "#fires");
        assertThat(MiniJson.str(fires, "scope")).isEqualTo("module");
        assertThat(((Number) requireNonNull(MiniJson.get(fires, "population"))).intValue())
                .isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Object> violations = (List<Object>) requireNonNull(MiniJson.get(fires, "violations"));
        assertThat(violations).hasSize(1);
        assertThat(MiniJson.str(violations.get(0), "fingerprint"))
                .isEqualTo("a.Esc#write()V -> java.lang.String#replace(CC)Ljava/lang/String;");
        assertThat(MiniJson.str(violations.get(0), "file")).isEqualTo("a/Esc.java");
        assertThat(((Number) requireNonNull(MiniJson.get(violations.get(0), "line"))).intValue())
                .isEqualTo(7);
        @SuppressWarnings("unchecked")
        List<Object> params = (List<Object>) MiniJson.get(fires, "params");
        assertThat(params).containsExactly("Facts", "Text", "Violations");
        assertThat(MiniJson.str(byId.get("tiers-ok"), "outcome")).isEqualTo("ok");
        assertThat((List<?>) MiniJson.get(byId.get("tiers-ok"), "violations")).isEmpty();
        assertThat(MiniJson.str(byId.get("boom"), "outcome")).isEqualTo("threw");
        assertThat(MiniJson.str(byId.get("boom"), "error")).contains("kaboom");
        assertThat(MiniJson.str(byId.get("needs-owner"), "outcome")).isEqualTo("owner-missing");
        assertThat(MiniJson.str(byId.get("needs-owner"), "error")).contains("a.Missing");
        assertThat(MiniJson.str(byId.get("no-tool"), "outcome")).isEqualTo("skipped");
        assertThat(MiniJson.str(byId.get("no-tool"), "error")).isEqualTo("shellcheck: not installed");
    }

    @Test
    void the_blanker_keeps_offsets_and_finds_literals() {
        String src = "a = \"x\"; // c \"y\"\n/* b */ b = 'q'; s = \"\"\"\n  t\n  \"\"\";";
        String noComments = Blanker.blank(src, true, false, false);
        assertThat(noComments).hasSize(src.length()).doesNotContain("// c").contains("\"x\"");
        assertThat(Blanker.blank(src, true, true, false)).contains("\" \"").doesNotContain("\"x\"");
        String commentsOnly = Blanker.blank(src, false, true, true);
        assertThat(commentsOnly).contains("// c \"y\"").contains("/* b */").doesNotContain("a =");
        assertThat(Blanker.literals(src)).containsExactly("x", "t");
        assertThat(TextView.globPattern("**/src/main/**/*.java")
                        .matcher("lib/src/main/java/a/B.java")
                        .matches())
                .isTrue();
        assertThat(TextView.globPattern("**/src/main/**/*.java")
                        .matcher("src/main/java/a/B.java")
                        .matches())
                .isTrue();
        assertThat(TextView.globPattern("*.java").matcher("a/B.java").matches()).isFalse();
    }

    @Test
    void the_config_round_trips_through_properties(@TempDir Path dir) throws Exception {
        GuardConfig c = new GuardConfig(
                dir.resolve("r.jsonl"),
                dir,
                "shared/core",
                List.of(dir.resolve("a.idx"), dir.resolve("b.idx")),
                List.of(),
                dir.resolve("m.json"),
                List.of(dir.resolve("src")),
                List.of(dir.resolve("classes")),
                List.of(),
                List.of(dir.resolve("x.jar")),
                null,
                false,
                null);
        Path f = dir.resolve("run.properties");
        Files.writeString(f, c.toProperties());
        assertThat(GuardConfig.read(f)).isEqualTo(c);
    }
}
