// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.command.Exit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.engine.Filter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * {@link LauncherPath} is the runner's only tag filter and only event emitter. These tests pin both
 * halves: the tag expressions JUnit parses on our behalf, and the two payload rules — the message
 * cap and the display fallback — that the wire depends on.
 *
 * <p>{@link Tagged} is driven through {@code runClass}; every one of its methods passes, because
 * classpath-root discovery (what {@code jk test} itself does to this module) walks nested classes
 * too, so a fixture that failed on purpose would fail the self-hosted build.
 *
 * <p>The empty-tier tests copy a class file into a root of its own, so the runner's discovery over
 * that root sees exactly the classes named: a tier every test of which the tag filter excludes
 * reports nothing, and only a root with no test under any filter raises the empty-discovery
 * warning.
 */
class LauncherPathTest {

    // --- the one tag filter --------------------------------------------------

    @Test
    void exclude_by_tag_name_drops_only_the_tagged_test() {
        assertThat(run(List.of(), List.of("slow"))).containsExactlyInAnyOrder("plain()", "bracketed()");
    }

    @Test
    void exclude_negation_is_a_tag_expression_not_a_tag_name() {
        // Exclude "!slow" is a JUnit tag expression; TagFilter is the only reader.
        assertThat(run(List.of(), List.of("!slow"))).containsExactly("slowOne()");
    }

    @Test
    void include_negation_is_a_tag_expression_too() {
        assertThat(run(List.of("!slow"), List.of())).containsExactlyInAnyOrder("plain()", "bracketed()");
    }

    @Test
    void include_by_tag_name_keeps_only_the_tagged_test() {
        assertThat(run(List.of("slow"), List.of())).containsExactly("slowOne()");
    }

    @Test
    void or_expressions_are_honoured() {
        assertThat(run(List.of("slow | brackets"), List.of())).containsExactlyInAnyOrder("slowOne()", "bracketed()");
    }

    @Test
    void a_tag_whose_name_contains_brackets_still_matches() {
        // "[slow]" is a legal TestTag and must filter, not truncate at ']'.
        assertThat(run(List.of(), List.of("[slow]"))).containsExactlyInAnyOrder("plain()", "slowOne()");
    }

    @Test
    void no_filters_runs_everything() {
        assertThat(run(List.of(), List.of())).containsExactlyInAnyOrder("plain()", "slowOne()", "bracketed()");
    }

    // --- a named class the tag filter dropped ------------------------------------

    @Test
    void a_named_class_the_exclude_filter_drops_is_reported_with_its_tag_and_the_flag_that_runs_it() {
        var events = new Recorder();
        LauncherPath.warnTagExcluded(
                named(TagExcludedFixture.class),
                List.of(),
                List.of("slow"),
                filtered(TagExcludedFixture.class, TagFilter.excludeTags("slow")),
                events,
                0);
        assertThat(events.warnings()).singleElement().satisfies(w -> {
            assertThat(w.get("code")).isEqualTo("tag-excluded");
            assertThat(String.valueOf(w.get("message")))
                    .contains(TagExcludedFixture.class.getName() + " [slow]")
                    .contains("pass --include-tags slow");
        });
    }

    @Test
    void a_named_class_the_include_filter_admits_raises_no_warning() {
        var events = new Recorder();
        LauncherPath.warnTagExcluded(
                named(TagExcludedFixture.class),
                List.of("slow"),
                List.of(),
                filtered(TagExcludedFixture.class, TagFilter.includeTags("slow")),
                events,
                0);
        assertThat(events.warnings()).isEmpty();
    }

    @Test
    void a_class_filter_with_no_tag_filter_discovers_once_and_warns_of_nothing() {
        var events = new Recorder();
        int[] discoveries = {0};
        LauncherPath.warnTagExcluded(
                () -> {
                    discoveries[0]++;
                    return named(TagExcludedFixture.class).get();
                },
                List.of(),
                List.of(),
                filtered(TagExcludedFixture.class),
                events,
                0);
        assertThat(events.warnings()).isEmpty();
        assertThat(discoveries[0])
                .as("no tag filter: nothing to compare against")
                .isZero();
    }

    @Test
    void the_message_names_an_untagged_class_an_include_filter_dropped() {
        String msg = LauncherPath.tagExcludedMessage(Map.of("a.Plain", Set.of()), List.of("integration"), List.of());
        assertThat(msg).contains("a.Plain (untagged)").contains("--include-tags integration admits only");
    }

    /** What {@code --class} names: a fresh request for exactly {@code c} on every call. */
    private static Supplier<@Nullable LauncherDiscoveryRequestBuilder> named(Class<?> c) {
        return () -> LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass(c));
    }

    /** The plan the run would execute: {@code c} under {@code filters}. */
    private static TestPlan filtered(Class<?> c, Filter<?>... filters) {
        return LauncherFactory.create()
                .discover(
                        Objects.requireNonNull(named(c).get()).filters(filters).build());
    }

    // --- a tier the tag filter emptied ---------------------------------------------

    @Test
    void a_root_whose_every_test_the_tag_filter_excludes_is_an_empty_tier_not_a_warning(@TempDir Path tmp)
            throws IOException {
        Path root = classpathRootOf(tmp, TagEmptiedFixture.class);
        var events = new Recorder();
        LauncherPath.runListOnly(root, null, List.of(), List.of("integration"), List.of(), 0, events);
        assertThat(events.warnings()).isEmpty();
        assertThat(events.finishedTests()).isEmpty();
    }

    @Test
    void a_root_whose_classes_declare_a_test_the_platform_cannot_run_warns_of_an_empty_discovery(@TempDir Path tmp)
            throws IOException {
        Path root = classpathRootOf(tmp, TestableOnlyFixture.class, EventType.class);
        var events = new Recorder();
        LauncherPath.runListOnly(root, null, List.of(), List.of("integration"), List.of(), 0, events);
        assertThat(events.warnings()).singleElement().satisfies(w -> {
            assertThat(w.get("code")).isEqualTo(LauncherPath.NO_TESTS_DISCOVERED);
            assertThat(String.valueOf(w.get("message"))).contains("no tests discovered in 2 classes");
        });
    }

    @Test
    void a_root_whose_classes_declare_no_test_framework_is_an_empty_run_naming_them(@TempDir Path tmp)
            throws IOException {
        Path root = classpathRootOf(tmp, EventType.class, Exit.class);
        var events = new Recorder();
        LauncherPath.runListOnly(root, null, List.of(), List.of(), List.of(), 0, events);
        assertThat(events.warnings()).singleElement().satisfies(w -> {
            assertThat(w.get("code")).isEqualTo("no-test-classes");
            assertThat(String.valueOf(w.get("message")))
                    .startsWith("no test classes: none of the 2 classes under " + root)
                    .contains("so nothing ran — " + Exit.class.getName() + ", " + EventType.class.getName());
        });
        assertThat(events.finishedTests()).isEmpty();
    }

    @Test
    void a_classpath_with_no_engine_over_classes_that_declare_no_test_is_the_same_empty_run(@TempDir Path tmp)
            throws IOException {
        Path root = classpathRootOf(tmp, EventType.class);
        RuntimeException noEngine = new IllegalStateException(
                "Cannot create Launcher without at least one TestEngine; consider adding an engine implementation JAR to the classpath");
        var events = new Recorder();

        assertThat(LauncherPath.emptyRunWithoutEngine(noEngine, root, null, events, 0))
                .isTrue();
        assertThat(events.types).containsExactly(EventType.DISCOVERY_TOTAL, EventType.WARNING);
        assertThat(events.warnings()).singleElement().satisfies(w -> {
            assertThat(w.get("code")).isEqualTo(LauncherPath.NO_TEST_CLASSES);
            assertThat(String.valueOf(w.get("message"))).contains(EventType.class.getName());
        });

        Path declared = classpathRootOf(tmp.resolve("declared"), TestableOnlyFixture.class);
        assertThat(LauncherPath.emptyRunWithoutEngine(noEngine, declared, null, new Recorder(), 0))
                .as("a declared test keeps the missing engine a failure")
                .isFalse();
        assertThat(LauncherPath.emptyRunWithoutEngine(noEngine, root, "com.example.Named", new Recorder(), 0))
                .as("a class filter is judged by the engine across the run")
                .isFalse();
        assertThat(LauncherPath.emptyRunWithoutEngine(new IllegalStateException("boom"), root, null, new Recorder(), 0))
                .as("only the no-engine refusal is judged")
                .isFalse();
    }

    /** A classpath root holding only {@code classes}, copied out of this module's own output. */
    private static Path classpathRootOf(Path tmp, Class<?>... classes) throws IOException {
        for (Class<?> c : classes) {
            Path file = tmp.resolve(c.getName().replace('.', '/') + ".class");
            Files.createDirectories(Objects.requireNonNull(file.getParent()));
            try (InputStream in =
                    Objects.requireNonNull(c.getResourceAsStream(c.getSimpleName() + ".class"), c.getName())) {
                Files.copy(in, file);
            }
        }
        return tmp;
    }

    // --- discovery ---------------------------------------------------------------
    /**
     * A list-only discovery names classes and runs nothing, so the session and discovery
     * listeners the classpath registers — a framework's hook to boot the application the tests will
     * run against — do not fire in it. The control discovery shows the fixture is wired.
     */
    @Test
    void a_list_only_discovery_fires_no_registered_session_or_discovery_listener(@TempDir Path tmp) throws IOException {
        Path root = classpathRootOf(tmp, TagEmptiedFixture.class);
        LifecycleListenerFixture.reset();
        LauncherFactory.create()
                .discover(LauncherDiscoveryRequestBuilder.request()
                        .selectors(DiscoverySelectors.selectClasspathRoots(Set.of(root)))
                        .build());
        assertThat(LifecycleListenerFixture.sessionsOpened())
                .as("control: a plain launcher")
                .isEqualTo(1);
        assertThat(LifecycleListenerFixture.discoveriesStarted())
                .as("control: a plain launcher")
                .isEqualTo(1);

        LifecycleListenerFixture.reset();
        var events = new Recorder();
        LauncherPath.runListOnly(root, null, List.of(), List.of(), List.of("slow"), 0, events);
        assertThat(events.discovered()).containsExactly(TagEmptiedFixture.class.getName());
        assertThat(LifecycleListenerFixture.sessionsOpened()).isZero();
        assertThat(LifecycleListenerFixture.discoveriesStarted()).isZero();
    }

    /**
     * A one-shot run lists the root on the listing launcher — no session or discovery listener
     * fires for the root — and executes class by class, so a framework readies the JVM for the
     * class about to run and not for every class the root holds: each class is one discovery that
     * selects it, and every test the root holds still runs.
     */
    @Test
    void a_one_shot_run_lists_without_listeners_and_executes_one_class_at_a_time(@TempDir Path tmp) throws IOException {
        Path root = classpathRootOf(tmp, TagEmptiedFixture.class, PlainPassingFixture.class);
        LifecycleListenerFixture.reset();
        var events = new Recorder();

        int exit = LauncherPath.runOneShot(root, null, List.of(), List.of(), List.of(), 0, events);

        assertThat(exit).isZero();
        assertThat(events.discovered())
                .containsExactlyInAnyOrder(TagEmptiedFixture.class.getName(), PlainPassingFixture.class.getName());
        assertThat(events.finishedTests()).hasSize(3);
        assertThat(LifecycleListenerFixture.rootDiscoveries())
                .as("the root is listed on a launcher that fires no listener")
                .isZero();
        assertThat(LifecycleListenerFixture.classDiscoveries())
                .as("one executing discovery per class")
                .isEqualTo(2);
        assertThat(LifecycleListenerFixture.sessionsOpened()).isEqualTo(2);
    }

    @Test
    void discovery_announces_a_nested_class_through_its_enclosing_class_only() {
        assertThat(LauncherPath.discoveredClassesOf(WithNested.class)).containsExactly(WithNested.class.getName());
    }

    @Test
    void a_parameterized_invocation_carries_its_display_name_and_a_plain_test_does_not() {
        var events = new Recorder();
        LauncherPath.runClass(Parameterized.class.getName(), List.of(), List.of(), List.of(), 0, events);
        List<Map<String, Object>> finished = events.finishedTests();
        assertThat(finished).hasSize(3);
        assertThat(finished.stream().filter(e -> e.containsKey("display")).map(e -> e.get("display")))
                .containsExactlyInAnyOrder("[1] \"a\"", "[2] \"b\"");
        assertThat(finished.stream().filter(e -> !e.containsKey("display")).map(e -> e.get("testMethod")))
                .containsExactly("plain()");
    }

    // --- the one emitter -----------------------------------------------------

    @Test
    void finished_events_carry_split_identity_status_and_worker_id() {
        var events = new Recorder();
        LauncherPath.runClass(Tagged.class.getName(), List.of(), List.of("slow"), List.of(), 7, events);
        Map<String, Object> finished = events.finishedTests().get(0);
        assertThat(finished)
                .containsEntry("testClass", Tagged.class.getName())
                .containsEntry("testMethod", "slowOne()")
                .containsEntry("status", "SUCCESSFUL")
                .containsEntry("type", "TEST")
                .containsEntry("worker", 7)
                .containsKey("uniqueId")
                .containsKey("duration_ms");
        assertThat(finished).doesNotContainKey("display");
    }

    @Test
    void a_passing_run_reports_no_failures() {
        assertThat(LauncherPath.runClass(Tagged.class.getName(), List.of(), List.of(), List.of(), 0, new Recorder()))
                .isFalse();
    }

    // --- a method selection ------------------------------------------------------

    @Test
    void a_method_selection_runs_one_method_of_the_named_class() {
        var events = new Recorder();
        LauncherPath.runClass(
                Tagged.class.getName(), selecting(Tagged.class, "slowOne"), List.of(), List.of(), 0, events);
        assertThat(methods(events)).containsExactly("slowOne()");
    }

    @Test
    void a_method_glob_selects_every_matching_method() {
        var events = new Recorder();
        LauncherPath.runClass(Tagged.class.getName(), selecting(Tagged.class, "*One"), List.of(), List.of(), 0, events);
        assertThat(methods(events)).containsExactly("slowOne()");
    }

    @Test
    void a_nested_class_is_reached_through_its_outermost_class() {
        var events = new Recorder();
        LauncherPath.runClass(
                WithNested.class.getName(), selecting(WithNested.class, "inner"), List.of(), List.of(), 0, events);
        assertThat(methods(events)).containsExactly("inner()");
    }

    @Test
    void a_class_no_selection_names_runs_whole() {
        var events = new Recorder();
        LauncherPath.runClass(
                Tagged.class.getName(), selecting(Parameterized.class, "plain"), List.of(), List.of(), 0, events);
        assertThat(methods(events)).containsExactlyInAnyOrder("plain()", "slowOne()", "bracketed()");
    }

    @Test
    void the_argument_names_a_class_regex_and_a_method_or_is_refused() {
        List<MethodSelection> one = MethodSelection.parse(List.of("^(a\\.B)$#c"));
        assertThat(one).singleElement().satisfies(s -> {
            assertThat(s.namesClass("a.B")).isTrue();
            assertThat(s.namesClass("a.B$Inner")).isTrue();
            assertThat(s.namesClass("a.BC")).isFalse();
            assertThat(s.methodPattern().matcher("c").matches()).isTrue();
        });
        assertThatThrownBy(() -> MethodSelection.parse(List.of("a.B")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#");
        assertThatThrownBy(() -> MethodSelection.parse(List.of("a.B#")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no method");
    }

    /** {@code --class <c>#<method>} as the engine spells it for the runner: an anchored class regex. */
    private static List<MethodSelection> selecting(Class<?> c, String method) {
        return MethodSelection.parse(List.of("^" + Pattern.quote(c.getName()) + "$#" + method));
    }

    private static List<String> methods(Recorder events) {
        return events.finishedTests().stream()
                .map(p -> String.valueOf(p.get("testMethod")))
                .toList();
    }

    // --- the message cap -----------------------------------------------------

    /**
     * The engine's {@code JUnitLauncher.ResultAggregator.MESSAGE_TRUNCATION_MARKER}. Spelled out
     * here because the engine is not on this worker's classpath — the two must stay identical or
     * the engine stops recognising a worker-capped message and re-cuts it.
     */
    private static final String MARKER = " ... message truncated (";

    private static final int CAP = 8_192;

    @Test
    void an_oversized_failure_message_is_capped_on_the_wire() {
        Map<String, Object> m = LauncherPath.throwableMap(new IllegalStateException("x".repeat(20_000)));
        String message = (String) m.get("message");
        assertThat(message).startsWith("x".repeat(CAP)).endsWith(MARKER + (20_000 - CAP) + " more chars)");
        assertThat(message)
                .hasSize(CAP + MARKER.length() + String.valueOf(20_000 - CAP).length() + " more chars)".length());
        assertThat(m).containsEntry("class", "java.lang.IllegalStateException");
        assertThat((String) m.get("stack")).contains("java.lang.IllegalStateException");
    }

    @Test
    void a_message_at_the_cap_is_left_alone() {
        String exact = "y".repeat(CAP);
        assertThat(LauncherPath.throwableMap(new IllegalStateException(exact))).containsEntry("message", exact);
        assertThat(LauncherPath.throwableMap(new IllegalStateException())).containsEntry("message", "");
    }

    @Test
    void the_cap_never_splits_a_surrogate_pair() {
        // Emoji straddling the cut: the high surrogate sits at index CAP-1, so the cut must back
        // off by one rather than emit a lone code unit the parent's JSON decoder would mangle.
        String message = "z".repeat(CAP - 1) + "😀" + "z".repeat(100);
        String capped = (String) Objects.requireNonNull(
                LauncherPath.throwableMap(new IllegalStateException(message)).get("message"));
        assertThat(Character.isHighSurrogate(capped.charAt(CAP - 2))).isFalse();
        assertThat(capped).startsWith("z".repeat(CAP - 1)).contains(MARKER);
        assertThat(capped.indexOf(MARKER)).isEqualTo(CAP - 1);
    }

    // --- the display fallback ------------------------------------------------

    @Test
    void a_jupiter_id_needs_no_display_name() {
        var payload = new LinkedHashMap<String, Object>();
        LauncherPath.putIdentity("[engine:junit-jupiter]/[class:a.B]/[method:c()]", "c()", payload);
        assertThat(payload)
                .containsEntry("testEngine", "junit-jupiter")
                .containsEntry("testClass", "a.B")
                .containsEntry("testMethod", "c()")
                .doesNotContainKey("display");
    }

    @Test
    void an_engine_with_no_class_or_method_segments_falls_back_to_the_display_name() {
        // Spock / Cucumber: without this the parent's progress and FAILED labels regress to the
        // raw bracketed uniqueId.
        var payload = new LinkedHashMap<String, Object>();
        LauncherPath.putIdentity(
                "[engine:spock]/[spec:MySpec]/[feature:$spock_feature_0_0]", "adds two numbers", payload);
        assertThat(payload).containsEntry("display", "adds two numbers").doesNotContainKey("testClass");
    }

    @Test
    void a_blank_display_name_adds_no_key() {
        var payload = new LinkedHashMap<String, Object>();
        LauncherPath.putIdentity("[engine:spock]/[spec:MySpec]", "  ", payload);
        assertThat(payload).containsEntry("testEngine", "spock").doesNotContainKey("display");
        LauncherPath.putIdentity("[engine:spock]/[spec:MySpec]", null, payload);
        assertThat(payload).doesNotContainKey("display");
    }

    // --- fixtures ------------------------------------------------------------

    private static List<String> run(List<String> include, List<String> exclude) {
        var events = new Recorder();
        LauncherPath.runClass(Tagged.class.getName(), List.of(), include, exclude, 0, events);
        return events.finishedTests().stream()
                .map(p -> String.valueOf(p.get("testMethod")))
                .toList();
    }

    /** Captures every event the emitter writes, in order. */
    private static final class Recorder implements EventWriter {
        private final List<Map<String, Object>> events = new ArrayList<>();
        private final List<EventType> types = new ArrayList<>();

        @Override
        public void write(EventType type, Map<String, Object> payload) {
            types.add(type);
            events.add(new LinkedHashMap<>(payload));
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        List<Map<String, Object>> warnings() {
            var out = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i) == EventType.WARNING) out.add(events.get(i));
            }
            return out;
        }

        List<String> discovered() {
            var out = new ArrayList<String>();
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i) == EventType.DISCOVERED)
                    out.add(String.valueOf(events.get(i).get("class")));
            }
            return out;
        }

        List<Map<String, Object>> finishedTests() {
            var out = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i) == EventType.FINISHED
                        && "TEST".equals(events.get(i).get("type"))) {
                    out.add(events.get(i));
                }
            }
            return out;
        }
    }

    /** Two invocations of one method, and a plain test beside them. */
    static class Parameterized {

        @ParameterizedTest
        @ValueSource(strings = {"a", "b"})
        void takes(String value) {}

        @Test
        void plain() {}
    }

    /** A class with a {@code @Nested} child: one class to the pull workers, not two. */
    static class WithNested {

        @Test
        void outer() {}

        @Nested
        class Inner {
            @Test
            void inner() {}
        }
    }

    /** Named by {@code --class} and dropped by an exclude of its class-level tag; its one method passes. */
    @Tag("slow")
    static class TagExcludedFixture {

        @Test
        void tagged() {}
    }

    /** Driven through {@code runClass}; every method passes so any other discoverer sees green. */
    static class Tagged {

        @Test
        void plain() {}

        @Test
        @Tag("slow")
        void slowOne() {}

        @Test
        @Tag("[slow]")
        @Tag("brackets")
        void bracketed() {}
    }
}
