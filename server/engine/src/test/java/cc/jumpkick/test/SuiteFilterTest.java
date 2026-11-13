// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.model.DeclaredSuite;
import cc.jumpkick.model.SuiteSettings;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteFilterTest {

    private static final Map<String, DeclaredSuite> TIERS = tiers();

    /** jk's own shape: integration and slow claim tests by tag, slow out of integration. */
    private static Map<String, DeclaredSuite> tiers() {
        Map<String, DeclaredSuite> out = new LinkedHashMap<>();
        out.put("integration", new DeclaredSuite(List.of(), List.of("integration"), List.of(), List.of("slow")));
        out.put("slow", DeclaredSuite.ofTags(List.of("slow")));
        return out;
    }

    private static final Map<String, DeclaredSuite> FAILSAFE =
            Map.of("integration", DeclaredSuite.ofClasses(List.of("IT*", "*IT", "*ITCase")));

    @Test
    void class_patterns_select_by_the_runners_grammar() {
        var bare = SuiteFilter.selects(List.of("TestEffortTest"));
        assertThat(bare.test("cc.jumpkick.runtime.TestEffortTest")).isTrue();
        assertThat(bare.test("cc.jumpkick.runtime.OtherTestEffortTest")).isFalse();

        var qualified = SuiteFilter.selects(List.of("cc.jumpkick.runtime.TestEffortTest#weight"));
        assertThat(qualified.test("cc.jumpkick.runtime.TestEffortTest")).isTrue();
        assertThat(qualified.test("cc.jumpkick.other.TestEffortTest")).isFalse();

        var wild = SuiteFilter.selects(List.of("Step*Test"));
        assertThat(wild.test("cc.jumpkick.runtime.StepLedgerTest")).isTrue();
        assertThat(wild.test("cc.jumpkick.runtime.LedgerTest")).isFalse();

        assertThat(SuiteFilter.selects(List.of()).test("a.ATest")).isFalse();
    }

    @Test
    void the_default_suite_leaves_every_pattern_suite_class_out(@TempDir Path module) {
        SuiteFilter suites = SuiteFilter.of(module, false, List.of("test"), FAILSAFE);
        Pattern runs = runs(suites);

        assertThat(runs.matcher("com.acme.FooTest").matches()).isTrue();
        assertThat(runs.matcher("com.acme.Items").matches()).isTrue();
        assertThat(runs.matcher("com.acme.FooIT").matches()).isFalse();
        assertThat(runs.matcher("com.acme.ITFoo").matches()).isFalse();
        assertThat(runs.matcher("com.acme.FooITCase").matches()).isFalse();
        assertThat(runs.matcher("com.acme.ITFoo$Nested").matches())
                .as("a nested class goes with its outer class")
                .isFalse();
        assertThat(suites.describe())
                .isEqualTo("test suites: test; not run: integration (IT*, *IT, *ITCase)," + " use jk test -s <name>");
    }

    @Test
    void a_pattern_suite_runs_only_its_classes(@TempDir Path module) {
        SuiteFilter suites = SuiteFilter.of(module, false, List.of("integration"), FAILSAFE);
        Pattern runs = runs(suites);

        assertThat(runs.matcher("com.acme.FooIT").matches()).isTrue();
        assertThat(runs.matcher("com.acme.ITFoo$Nested").matches()).isTrue();
        assertThat(runs.matcher("com.acme.FooTest").matches()).isFalse();
        assertThat(suites.describe())
                .isEqualTo("test suites: integration; integration runs test classes matching" + " IT*, *IT, *ITCase");
        assertThat(suites.ownersLeftOut("com.acme.FooTest")).containsExactly("test");
        assertThat(suites.ownersLeftOut("com.acme.FooIT")).isEmpty();
    }

    @Test
    void the_guard_and_all_keep_every_class() {
        Path module = Path.of("unused");
        assertThat(SuiteFilter.of(module, false, List.of("test", "integration"), FAILSAFE)
                        .body())
                .isNull();
        assertThat(SuiteFilter.of(module, false, List.of("test"), Map.of())).isSameAs(SuiteFilter.NONE);
        assertThat(SuiteFilter.NONE.body()).isNull();
        assertThat(SuiteFilter.NONE.describe()).isNull();
    }

    @Test
    void another_suite_directory_keeps_its_classes_whatever_their_names(@TempDir Path module) throws Exception {
        Path e2e = Files.createDirectories(module.resolve("src/e2e/java/com/acme"));
        Files.writeString(e2e.resolve("CheckoutIT.java"), "package com.acme; class CheckoutIT {}");
        SuiteFilter suites = SuiteFilter.of(module, false, List.of("test", "e2e"), FAILSAFE);
        Pattern runs = runs(suites);

        assertThat(runs.matcher("com.acme.CheckoutIT").matches()).isTrue();
        assertThat(runs.matcher("com.acme.FooIT").matches()).isFalse();
        assertThat(runs.matcher("com.acme.FooTest").matches()).isTrue();
    }

    @Test
    void a_directory_suite_run_apart_keeps_its_own_classes_and_leaves_the_rest(@TempDir Path module) throws Exception {
        Path e2e = Files.createDirectories(module.resolve("src/e2e/java/com/acme"));
        Files.writeString(e2e.resolve("CheckoutIT.java"), "package com.acme; class CheckoutIT {}");
        SuiteFilter suites = SuiteFilter.of(module, false, List.of("test", "integration", "e2e"), FAILSAFE);

        Pattern withDefault = runs(Objects.requireNonNull(suites.part("test")));
        assertThat(withDefault.matcher("com.acme.FooTest").matches()).isTrue();
        assertThat(withDefault.matcher("com.acme.FooIT").matches())
                .as("a class-pattern suite runs beside the default suite's resources")
                .isTrue();
        assertThat(withDefault.matcher("com.acme.CheckoutIT").matches()).isFalse();
        assertThat(withDefault.matcher("com.acme.CheckoutIT$Nested").matches()).isFalse();

        Pattern alone = runs(Objects.requireNonNull(suites.part("e2e")));
        assertThat(alone.matcher("com.acme.CheckoutIT").matches()).isTrue();
        assertThat(alone.matcher("com.acme.CheckoutIT$Nested").matches()).isTrue();
        assertThat(alone.matcher("com.acme.FooTest").matches()).isFalse();
        assertThat(alone.matcher("com.acme.FooIT").matches()).isFalse();

        assertThat(SuiteFilter.of(module, false, List.of("e2e"), Map.of()).part("test"))
                .as("a selection with nothing beside the default suite's resources")
                .isNull();
    }

    @Test
    void a_class_pattern_naming_a_left_out_class_names_its_suite(@TempDir Path classes) throws Exception {
        Files.createDirectories(classes.resolve("com/acme"));
        for (String name : List.of("FooTest", "FooIT", "FooIT$Inner", "BarIT")) {
            Files.writeString(classes.resolve("com/acme/" + name + ".class"), "");
        }
        SuiteFilter suites = SuiteFilter.of(classes, false, List.of("test"), FAILSAFE);

        assertThat(suites.classHint(classes, List.of("FooIT")))
                .isEqualTo("FooIT is in the integration suite (jk test -s integration -S FooIT)");
        assertThat(suites.classHint(classes, List.of("*IT")))
                .isEqualTo("BarIT, FooIT are in the integration suite (jk test -s integration -S *IT)");
        assertThat(suites.classHint(classes, List.of("FooTest"))).isNull();
        assertThat(SuiteFilter.of(classes, false, List.of("integration"), FAILSAFE)
                        .classHint(classes, List.of("FooTest")))
                .isEqualTo("FooTest is in the test suite (jk test -s test -S FooTest)");
    }

    @Test
    void the_class_files_say_whether_the_patterns_name_a_class_this_run_would_keep(@TempDir Path classes)
            throws Exception {
        Files.createDirectories(classes.resolve("com/acme"));
        for (String name : List.of("FooTest", "FooTest$Nested", "FooIT", "SlowTest")) {
            Files.writeString(classes.resolve("com/acme/" + name + ".class"), "");
        }
        SuiteFilter suites = SuiteFilter.of(classes, false, List.of("test"), FAILSAFE);

        assertThat(suites.namesCompiledClass(classes, List.of("FooTest"), List.of()))
                .isTrue();
        assertThat(suites.namesCompiledClass(classes, List.of("com.acme.FooTest#refunds"), List.of()))
                .isTrue();
        assertThat(suites.namesCompiledClass(classes, List.of("*Nested"), List.of()))
                .as("a nested class is a class the runner can be asked for")
                .isTrue();
        assertThat(suites.namesCompiledClass(classes, List.of("OrdersTest"), List.of()))
                .as("a module without the class skips without a test JVM")
                .isFalse();
        assertThat(suites.namesCompiledClass(classes, List.of("FooIT"), List.of()))
                .as("the integration suite owns it; this run does not")
                .isFalse();
        assertThat(suites.namesCompiledClass(classes, List.of("SlowTest"), List.of("*Slow*")))
                .as("[test] exclude-classes")
                .isFalse();
        assertThat(suites.namesCompiledClass(classes.resolve("absent"), List.of("FooTest"), List.of()))
                .isFalse();
    }

    /** A Failsafe exclude stays the suite's, so the default suite never takes it, and runs nowhere. */
    @Test
    void an_excluded_class_runs_in_no_suite(@TempDir Path module) {
        Map<String, DeclaredSuite> failsafe =
                Map.of("integration", DeclaredSuite.ofClasses(List.of("*IT"), List.of("*SlowIT")));
        Pattern unit = runs(SuiteFilter.of(module, false, List.of("test"), failsafe));
        Pattern integration = runs(SuiteFilter.of(module, false, List.of("integration"), failsafe));
        Pattern both = runs(SuiteFilter.of(module, false, List.of("test", "integration"), failsafe));

        assertThat(unit.matcher("com.acme.FooTest").matches()).isTrue();
        assertThat(unit.matcher("com.acme.CartSlowIT").matches()).isFalse();
        assertThat(integration.matcher("com.acme.CartIT").matches()).isTrue();
        assertThat(integration.matcher("com.acme.CartSlowIT").matches()).isFalse();
        assertThat(integration.matcher("com.acme.CartSlowIT$Nested").matches()).isFalse();
        assertThat(both.matcher("com.acme.FooTest").matches()).isTrue();
        assertThat(both.matcher("com.acme.CartIT").matches()).isTrue();
        assertThat(both.matcher("com.acme.CartSlowIT").matches()).isFalse();
    }

    /** Each class runs in exactly one suite, and a failure names it. */
    @Test
    void suite_of_names_the_suite_a_class_runs_in(@TempDir Path module) throws Exception {
        Path e2e = Files.createDirectories(module.resolve("src/e2e/java/com/acme"));
        Files.writeString(e2e.resolve("CheckoutIT.java"), "package com.acme; class CheckoutIT {}");
        SuiteFilter all = SuiteFilter.of(module, false, List.of("test", "integration", "e2e"), FAILSAFE);

        assertThat(all.suiteOf("com.acme.FooTest")).isEqualTo("test");
        assertThat(all.suiteOf("com.acme.FooIT")).isEqualTo("integration");
        assertThat(all.suiteOf("com.acme.FooIT$Inner")).isEqualTo("integration");
        assertThat(all.suiteOf("com.acme.CheckoutIT"))
                .as("a class of another suite directory is that suite's, whatever its name")
                .isEqualTo("e2e");
        assertThat(SuiteFilter.of(module, false, List.of("e2e"), Map.of()).suiteOf("com.acme.CheckoutIT"))
                .as("a directory suite without class-pattern suites")
                .isEqualTo("e2e");
        assertThat(SuiteFilter.NONE.suiteOf("com.acme.FooTest")).isEqualTo("test");

        TestSummary red = new TestSummary(
                        3,
                        1,
                        2,
                        0,
                        List.of(
                                new TestFailureInfo("g:m", "", "com.acme.FooIT", "x()", "", "boom", ""),
                                new TestFailureInfo("g:m", "", "", "(test run)", "", "worker exited 1", "")))
                .withSuites(all::suiteOf);
        assertThat(red.failures()).extracting(TestFailureInfo::suite).containsExactly("integration", "");
    }

    @Test
    void the_default_suite_leaves_every_claimed_tag_to_the_runner(@TempDir Path module) {
        SuiteFilter unit = SuiteFilter.of(module, false, List.of("test"), TIERS);

        assertThat(unit.body()).as("a tag claims tests, not classes").isNull();
        assertThat(unit.members()).containsExactly("|integration,slow|.*");
        assertThat(unit.describe())
                .isEqualTo("test suites: test; not run: integration (tagged integration), slow (tagged slow),"
                        + " use jk test -s <name>");
    }

    @Test
    void a_tag_suite_runs_its_tagged_tests_less_its_exclusions(@TempDir Path module) {
        SuiteFilter integration = SuiteFilter.of(module, false, List.of("integration"), TIERS);

        assertThat(integration.members()).containsExactly("integration|slow|.*");
        assertThat(integration.describe())
                .isEqualTo("test suites: integration; integration runs tests tagged integration");
        assertThat(SuiteFilter.of(module, false, List.of("test", "integration"), TIERS)
                        .members())
                .as("the push gate: the default suite's tests, then integration's")
                .containsExactly("|integration,slow|.*", "integration|slow|.*");
    }

    @Test
    void a_suite_claims_by_class_and_by_tag_at_once(@TempDir Path module) {
        Map<String, DeclaredSuite> both = Map.of(
                "integration",
                new DeclaredSuite(List.of("*IT"), List.of("integration"), List.of("*SlowIT"), List.of()));
        SuiteFilter integration = SuiteFilter.of(module, false, List.of("integration"), both);
        SuiteFilter unit = SuiteFilter.of(module, false, List.of("test"), both);

        assertThat(member(integration, 0, "com.acme.CartIT")).isTrue();
        assertThat(member(integration, 0, "com.acme.CartSlowIT")).isFalse();
        assertThat(integration.members().get(1)).startsWith("integration||");
        assertThat(member(integration, 1, "com.acme.FooTest"))
                .as("a tagged test of any class")
                .isTrue();
        assertThat(member(integration, 1, "com.acme.CartSlowIT")).isFalse();
        assertThat(unit.members()).singleElement().satisfies(m -> assertThat(m).startsWith("|integration|"));
        assertThat(member(unit, 0, "com.acme.CartIT"))
                .as("the class pattern still claims it")
                .isFalse();
        assertThat(member(unit, 0, "com.acme.FooTest")).isTrue();
    }

    @Test
    void a_tag_suite_claims_from_every_suite_directory_and_runs_beside_each(@TempDir Path module) throws Exception {
        Files.createDirectories(module.resolve("src/test/java/com/acme"));
        Files.writeString(module.resolve("src/test/java/com/acme/FooTest.java"), "package com.acme; class FooTest {}");
        Path e2e = Files.createDirectories(module.resolve("src/e2e/java/com/acme"));
        Files.writeString(e2e.resolve("CheckoutE2e.java"), "package com.acme; class CheckoutE2e {}");
        SuiteFilter slow = SuiteFilter.of(module, false, List.of("slow"), TIERS);

        SuiteFilter beside = Objects.requireNonNull(slow.part("e2e"));
        assertThat(runs(beside).matcher("com.acme.CheckoutE2e").matches()).isTrue();
        assertThat(runs(beside).matcher("com.acme.FooTest").matches()).isFalse();
        assertThat(beside.members()).singleElement().satisfies(m -> assertThat(m)
                .startsWith("slow||"));
        assertThat(slow.part("test"))
                .as("the default suite's sources hold tagged tests too")
                .isNotNull();

        SuiteFilter e2eRun = SuiteFilter.of(module, false, List.of("e2e"), TIERS);
        assertThat(e2eRun.members())
                .as("an undeclared directory suite leaves claimed tags out")
                .containsExactly("|integration,slow|.*");
    }

    @Test
    void read_tags_place_a_class_in_its_suite(@TempDir Path module) {
        Map<String, ClassTags> read = Map.of(
                "com.acme.CartIT", new ClassTags(Set.of("integration"), Set.of("integration")),
                "com.acme.MixedTest", new ClassTags(Set.of(), Set.of("slow")),
                "com.acme.HeavyIT", new ClassTags(Set.of("integration", "slow"), Set.of("integration", "slow")));
        SuiteFilter gate = SuiteFilter.of(module, false, List.of("test", "integration"), TIERS)
                .withTags(read::get);

        assertThat(gate.suiteOf("com.acme.CartIT")).isEqualTo("integration");
        assertThat(gate.suiteOf("com.acme.CartIT$Nested")).isEqualTo("integration");
        assertThat(gate.suiteOf("com.acme.FooTest")).isEqualTo("test");
        assertThat(gate.acceptor().test("com.acme.CartIT")).isTrue();
        assertThat(gate.acceptor().test("com.acme.MixedTest"))
                .as("a method-level tag leaves the class's other tests in the default suite")
                .isTrue();
        assertThat(gate.acceptor().test("com.acme.HeavyIT"))
                .as("claimed by slow, excluded by integration")
                .isFalse();
        SuiteFilter unit = SuiteFilter.of(module, false, List.of("test"), TIERS).withTags(read::get);
        assertThat(unit.ownersLeftOut("com.acme.CartIT")).containsExactly("integration");
        assertThat(unit.ownersLeftOut("com.acme.FooTest")).isEmpty();
        assertThat(unit.ownersLeftOut("com.acme.HeavyIT"))
                .as("integration excludes it")
                .isEmpty();
    }

    @Test
    void without_a_claimed_tag_the_runner_needs_no_members(@TempDir Path module) {
        assertThat(SuiteFilter.of(module, false, List.of("test"), FAILSAFE).members())
                .isEmpty();
        assertThat(SuiteFilter.NONE.members()).isEmpty();
    }

    @Test
    void a_suite_with_settings_runs_in_jvms_of_its_own(@TempDir Path module) {
        Map<String, DeclaredSuite> suites = new LinkedHashMap<>();
        SuiteSettings env = new SuiteSettings(Map.of("DENY", "remote"), List.of(), Map.of(), null);
        suites.put(
                "integration", new DeclaredSuite(List.of(), List.of("integration"), List.of(), List.of(), env, false));
        suites.put("slow", DeclaredSuite.ofTags(List.of("slow")));
        SuiteFilter all = SuiteFilter.of(module, false, List.of("test", "integration", "slow"), suites);

        assertThat(all.launches())
                .extracting(SuiteFilter.Launch::suites, SuiteFilter.Launch::settings)
                .containsExactly(tuple(Set.of("test", "slow"), SuiteSettings.NONE), tuple(Set.of("integration"), env));
        assertThat(Objects.requireNonNull(all.only(Set.of("integration"))).members())
                .containsExactly("integration||.*");
        assertThat(Objects.requireNonNull(all.only(Set.of("test", "slow"))).members())
                .containsExactly("|integration,slow|.*", "slow||.*");
        assertThat(SuiteFilter.of(module, false, List.of("test", "slow"), suites)
                        .launches())
                .as("integration not selected: one launch, as before")
                .singleElement()
                .extracting(SuiteFilter.Launch::settings)
                .isEqualTo(SuiteSettings.NONE);
    }

    @Test
    void a_declared_suite_with_a_directory_runs_beside_its_own_resources(@TempDir Path module) throws Exception {
        Files.createDirectories(module.resolve("src/test/java/com/acme"));
        Files.writeString(module.resolve("src/test/java/com/acme/FooTest.java"), "package com.acme; class FooTest {}");
        Path e2e = Files.createDirectories(module.resolve("src/e2e/java/com/acme"));
        Files.writeString(e2e.resolve("CheckoutE2e.java"), "package com.acme; class CheckoutE2e {}");
        SuiteSettings env = new SuiteSettings(Map.of("BROWSER", "headless"), List.of(), Map.of(), null);
        Map<String, DeclaredSuite> suites =
                Map.of("e2e", new DeclaredSuite(List.of(), List.of(), List.of(), List.of(), env, false));
        SuiteFilter both = SuiteFilter.of(module, false, List.of("test", "e2e"), suites);

        Pattern beside = runs(Objects.requireNonNull(both.part("e2e")));
        assertThat(beside.matcher("com.acme.CheckoutE2e").matches()).isTrue();
        assertThat(beside.matcher("com.acme.FooTest").matches()).isFalse();
        assertThat(runs(Objects.requireNonNull(both.part("test")))
                        .matcher("com.acme.CheckoutE2e")
                        .matches())
                .isFalse();
        assertThat(Objects.requireNonNull(both.part("test")).only(Set.of("e2e")))
                .as("e2e runs nothing beside the default suite's resources")
                .isNull();
    }

    /** Whether clause {@code index} of {@code suites}' members admits {@code className} by class. */
    private static boolean member(SuiteFilter suites, int index, String className) {
        String member = suites.members().get(index);
        String classes = member.substring(member.indexOf('|', member.indexOf('|') + 1) + 1);
        return Pattern.compile("^(?:" + classes + ")$").matcher(className).matches();
    }

    private static Pattern runs(SuiteFilter suites) {
        return Pattern.compile(JUnitClassFilter.filter(null, null, suites.body()));
    }
}
