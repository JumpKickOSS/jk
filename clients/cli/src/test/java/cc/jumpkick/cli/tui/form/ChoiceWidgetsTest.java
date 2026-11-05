// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import static cc.jumpkick.cli.tui.form.Scripted.BACKSPACE;
import static cc.jumpkick.cli.tui.form.Scripted.DOWN;
import static cc.jumpkick.cli.tui.form.Scripted.ENTER;
import static cc.jumpkick.cli.tui.form.Scripted.LEFT;
import static cc.jumpkick.cli.tui.form.Scripted.RIGHT;
import static cc.jumpkick.cli.tui.form.Scripted.UP;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.tui.form.Scripted.Mode;
import cc.jumpkick.cli.tui.form.Scripted.Run;
import cc.jumpkick.form.Option;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The list select, the toggle list and the typeahead, scripted in each mode. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ChoiceWidgetsTest {

    private static final List<Option> LANGS =
            List.of(new Option("java", "Java", "The JVM's own"), Option.of("kotlin", "Kotlin"), Option.of("groovy"));

    private static final List<Option> MANY =
            IntStream.rangeClosed(1, 12).mapToObj(i -> Option.of("o" + i)).toList();

    private static final List<Option> CATALOGUE = List.of(
            new Option("spring-boot/webmvc", null, "Servlet stack"),
            new Option("spring-boot/webflux", null, "Reactive stack"),
            Option.of("quarkus/rest"),
            Option.of("cli"));

    private static List<Option> search(String query) {
        return CATALOGUE.stream()
                .filter(o -> o.id().contains(query.toLowerCase(Locale.ROOT)))
                .toList();
    }

    @ParameterizedTest
    @EnumSource(
            value = Mode.class,
            names = {"NERD", "ANSI"})
    void a_short_list_is_one_inline_row_moved_with_left_and_right(Mode mode) throws Exception {
        Run<String> run = Scripted.run(mode, new ListSelect("Language", null, LANGS, "kotlin"), RIGHT + ENTER, "");

        assertThat(run.answer()).contains("groovy");
        assertThat(run.frames().getFirst()).hasSize(2);
        assertThat(run.frames().getFirst().get(1))
                .contains("Java")
                .contains("Kotlin")
                .contains("groovy");
        assertThat(run.last()).containsExactly("□  Language ➜ groovy");
    }

    @ParameterizedTest
    @EnumSource(
            value = Mode.class,
            names = {"NERD", "ANSI"})
    void the_focused_option_shows_its_help_under_the_row(Mode mode) throws Exception {
        Run<String> run = Scripted.run(mode, new ListSelect("Language", null, LANGS, null), LEFT + RIGHT + ENTER, "");

        assertThat(run.answer()).contains("java");
        assertThat(run.frames().getFirst()).last().isEqualTo("   The JVM's own");
    }

    @Test
    void the_focused_segment_is_a_pill_in_nerd_mode_and_a_padded_chip_in_ansi_mode() throws Exception {
        Run<String> nerd = Scripted.run(Mode.NERD, new ListSelect("Language", null, LANGS, "kotlin"), ENTER, "");
        Run<String> ansi = Scripted.run(Mode.ANSI, new ListSelect("Language", null, LANGS, "kotlin"), ENTER, "");

        assertThat(nerd.written()).contains(Scripted.PILL_CAP).contains("Kotlin");
        assertThat(ansi.written()).doesNotContain(Scripted.PILL_CAP).contains(" Kotlin ");
    }

    @ParameterizedTest
    @EnumSource(
            value = Mode.class,
            names = {"NERD", "ANSI"})
    void a_long_list_is_vertical_moved_with_up_and_down_and_scrolls(Mode mode) throws Exception {
        Run<String> run = Scripted.run(mode, new ListSelect("Pick", null, MANY, null), UP + ENTER, "");

        assertThat(run.answer()).contains("o12");
        String scrolled = run.frameWith("12 of 12");
        assertThat(scrolled).contains("   ●  o12").contains("   ○  o3").doesNotContain("o2\n");
        assertThat(run.frames().getFirst()).contains("   ●  o1", "   ○  o10").doesNotContain("   ○  o11");
    }

    @Test
    void list_select_in_plain_mode_takes_a_position_an_id_a_label_or_the_default() throws Exception {
        assertThat(Scripted.run(Mode.PLAIN, select(), "", "3\n").answer()).contains("groovy");
        assertThat(Scripted.run(Mode.PLAIN, select(), "", "Kotlin\n").answer()).contains("kotlin");
        assertThat(Scripted.run(Mode.PLAIN, select(), "", "java\n").answer()).contains("java");
        Run<String> run = Scripted.run(Mode.PLAIN, select(), "", "9\n\n");
        assertThat(run.answer()).contains("kotlin");
        assertThat(run.written())
                .isEqualTo("Language\n"
                        + "  1) ( ) Java  The JVM's own\n"
                        + "  2) (*) Kotlin\n"
                        + "  3) ( ) groovy\n"
                        + "Select [2]: Pick 1-3 or a listed id.\n"
                        + "Select [2]: -> Kotlin\n");
    }

    private static ListSelect select() {
        return new ListSelect("Language", null, LANGS, "kotlin");
    }

    @ParameterizedTest
    @EnumSource(
            value = Mode.class,
            names = {"NERD", "ANSI"})
    void toggle_list_ticks_with_space_and_takes_the_ticks_in_option_order(Mode mode) throws Exception {
        var toggle = new ToggleList("Features", null, LANGS, List.of("groovy"));

        Run<List<String>> run = Scripted.run(mode, toggle, DOWN + " " + ENTER, "");

        assertThat(run.answer()).contains(List.of("kotlin", "groovy"));
        assertThat(run.frames().getFirst())
                .containsExactly(
                        "■  Features",
                        "   □  Java  The JVM's own",
                        "   □  Kotlin",
                        "   ■  groovy",
                        "   1 of 3 selected · space toggles · a all");
        assertThat(run.frameWith("2 of 3")).contains("   ■  Kotlin").contains("   □  Java");
        assertThat(run.last()).containsExactly("□  Features ➜ Kotlin, groovy");
    }

    @ParameterizedTest
    @EnumSource(
            value = Mode.class,
            names = {"NERD", "ANSI"})
    void a_toggles_every_option_and_again_clears_them(Mode mode) throws Exception {
        var toggle = new ToggleList("Features", null, LANGS, List.of());

        assertThat(Scripted.run(mode, toggle, "a" + ENTER, "").answer()).contains(List.of("java", "kotlin", "groovy"));
        var again = new ToggleList("Features", null, LANGS, List.of());
        assertThat(Scripted.run(mode, again, "aa" + ENTER, "").answer()).contains(List.of());
    }

    @Test
    void toggle_list_in_plain_mode_reads_positions_ids_all_none_and_empty_for_defaults() throws Exception {
        assertThat(Scripted.run(Mode.PLAIN, toggle(), "", "3 1\n").answer()).contains(List.of("java", "groovy"));
        assertThat(Scripted.run(Mode.PLAIN, toggle(), "", "kotlin,groovy\n").answer())
                .contains(List.of("kotlin", "groovy"));
        assertThat(Scripted.run(Mode.PLAIN, toggle(), "", "all\n").answer())
                .contains(List.of("java", "kotlin", "groovy"));
        assertThat(Scripted.run(Mode.PLAIN, toggle(), "", "none\n").answer()).contains(List.of());
        Run<List<String>> run = Scripted.run(Mode.PLAIN, toggle(), "", "rust\n\n");
        assertThat(run.answer()).contains(List.of("kotlin"));
        assertThat(run.written())
                .isEqualTo("Features\n"
                        + "  1) [ ] Java  The JVM's own\n"
                        + "  2) [x] Kotlin\n"
                        + "  3) [ ] groovy\n"
                        + "Select (e.g. 1 3, all, none) [2]: Pick 1-3 or a listed id.\n"
                        + "Select (e.g. 1 3, all, none) [2]: -> Kotlin\n");
    }

    private static ToggleList toggle() {
        return new ToggleList("Features", null, LANGS, List.of("kotlin"));
    }

    @ParameterizedTest
    @EnumSource(
            value = Mode.class,
            names = {"NERD", "ANSI"})
    void typeahead_narrows_on_each_key_and_takes_the_focused_match(Mode mode) throws Exception {
        var typeahead = new Typeahead("Template", null, ChoiceWidgetsTest::search, null);

        Run<String> run = Scripted.run(mode, typeahead, "web" + DOWN + ENTER, "");

        assertThat(run.answer()).contains("spring-boot/webflux");
        assertThat(run.frames().getFirst()).contains("   ○  quarkus/rest", "   ○  cli");
        assertThat(run.frameWith("   web▏"))
                .contains("   ●  spring-boot/webmvc  Servlet stack")
                .contains("   ○  spring-boot/webflux  Reactive stack")
                .doesNotContain("quarkus");
        assertThat(run.last()).containsExactly("□  Template ➜ spring-boot/webflux");
    }

    @ParameterizedTest
    @EnumSource(
            value = Mode.class,
            names = {"NERD", "ANSI"})
    void typeahead_says_when_nothing_matches_and_keeps_asking(Mode mode) throws Exception {
        var typeahead = new Typeahead("Template", null, ChoiceWidgetsTest::search, null);

        Run<String> run = Scripted.run(mode, typeahead, "zz" + ENTER + BACKSPACE.repeat(2) + "cl" + ENTER, "");

        assertThat(run.answer()).contains("cli");
        assertThat(run.frameWith("Nothing matches")).contains("   no match").contains("   Nothing matches 'zz'.");
    }

    @Test
    void typeahead_in_plain_mode_lists_several_matches_by_number_and_takes_one_outright() throws Exception {
        var typeahead = new Typeahead("Template", null, ChoiceWidgetsTest::search, null);

        Run<String> run = Scripted.run(Mode.PLAIN, typeahead, "", "zz\nspring\n2\n");

        assertThat(run.answer()).contains("spring-boot/webflux");
        assertThat(run.written())
                .isEqualTo("Template (type to search): Nothing matches 'zz'.\n"
                        + "Template (type to search):   1) spring-boot/webmvc\n"
                        + "  2) spring-boot/webflux\n"
                        + "Pick 1-2, or search again.\n"
                        + "Template (type to search): -> spring-boot/webflux\n");
        assertThat(Scripted.run(Mode.PLAIN, new Typeahead("T", null, ChoiceWidgetsTest::search, null), "", "quark\n")
                        .answer())
                .contains("quarkus/rest");
    }
}
