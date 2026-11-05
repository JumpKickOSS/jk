// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import static cc.jumpkick.cli.tui.form.Scripted.ENTER;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.tui.form.Scripted.Mode;
import cc.jumpkick.form.Option;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** What each mode may write, held for every widget: plain no escape byte, ANSI no private-use glyph. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ModeInvariantsTest {

    private static final List<Option> OPTIONS = List.of(Option.of("a", "Alpha"), Option.of("b"));

    static Stream<Supplier<Question<?>>> widgets() {
        return Stream.of(
                () -> new LinePrompt("Name", "help", "x", null, true),
                () -> new YesNo("Proceed", "help", true),
                () -> new ListSelect("Pick", "help", OPTIONS, "a"),
                () -> new ToggleList("Tick", "help", OPTIONS, List.of("b")),
                () -> new Typeahead("Find", "help", q -> OPTIONS, "a"),
                () -> new PathPrompt("Dir", "help", ".", true, PathPrompt.directories(Path.of("."))));
    }

    @ParameterizedTest
    @MethodSource("widgets")
    void plain_mode_writes_no_escape_byte_and_settles_on_the_default(Supplier<Question<?>> widget) throws Exception {
        Scripted.Run<?> run = Scripted.run(Mode.PLAIN, widget.get(), "", "\n");

        assertThat(run.answer()).isPresent();
        assertThat(run.written()).doesNotContain("\u001b").contains(Asker.SETTLED_PREFIX);
        assertThat(run.written().chars()).allMatch(c -> c < 0x80);
    }

    @ParameterizedTest
    @MethodSource("widgets")
    void ansi_mode_paints_no_private_use_glyph_and_nerd_mode_answers_the_same(Supplier<Question<?>> widget)
            throws Exception {
        Scripted.Run<?> ansi = Scripted.run(Mode.ANSI, widget.get(), ENTER, "");
        Scripted.Run<?> nerd = Scripted.run(Mode.NERD, widget.get(), ENTER, "");

        assertThat(ansi.written()).contains("\u001b[").doesNotContain(Scripted.PILL_CAP);
        assertThat(ansi.written().codePoints()).noneMatch(cp -> cp >= 0xE000 && cp <= 0xF8FF);
        assertThat(nerd.answer()).isEqualTo(ansi.answer()).isPresent();
        assertThat(nerd.last()).isEqualTo(ansi.last());
    }
}
