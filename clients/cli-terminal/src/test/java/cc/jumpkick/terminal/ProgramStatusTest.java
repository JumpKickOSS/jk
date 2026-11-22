// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.terminal.ProgramStatus.Answer;
import cc.jumpkick.terminal.ProgramStatus.Report;
import cc.jumpkick.terminal.ProgramStatus.Waiting;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ProgramStatusTest {

    private static final String ESC = "\u001b";
    private static final String ST = ESC + "\\";
    private static final String BEL = "\u0007";

    @Test
    void working_with_and_without_a_percentage() {
        assertThat(ProgramStatus.osc7501(Report.working(42)))
                .isEqualTo(ESC + "]7501;state=working:app=jk:progress=42" + ST);
        assertThat(ProgramStatus.osc7501(Report.working())).isEqualTo(ESC + "]7501;state=working:app=jk" + ST);
        assertThat(ProgramStatus.osc94(Report.working(42))).isEqualTo(ESC + "]9;4;1;42" + BEL);
        assertThat(ProgramStatus.osc94(Report.working())).isEqualTo(ESC + "]9;4;3" + BEL);
    }

    @Test
    void a_percentage_is_clamped_to_the_spec_range() {
        assertThat(ProgramStatus.osc7501(Report.working(140))).contains(":progress=100");
        assertThat(ProgramStatus.osc7501(Report.working(-3))).contains(":progress=0");
    }

    @Test
    void blocked_carries_its_kind_and_is_not_an_error() {
        assertThat(ProgramStatus.osc7501(Report.blocked(Waiting.PERMISSION)))
                .isEqualTo(ESC + "]7501;state=blocked:app=jk:kind=permission" + ST);
        assertThat(ProgramStatus.osc7501(Report.blocked(Waiting.QUESTION))).contains(":kind=question");
        assertThat(ProgramStatus.osc7501(Report.blocked(Waiting.AUTH))).contains(":kind=auth");
        assertThat(ProgramStatus.osc94(Report.blocked(Waiting.QUESTION))).isEqualTo(ESC + "]9;4;4" + BEL);
        assertThat(ProgramStatus.osc94(Report.blocked(Waiting.QUESTION)))
                .isNotEqualTo(ProgramStatus.osc94(Report.error()));
    }

    @Test
    void error_done_and_idle() {
        assertThat(ProgramStatus.osc7501(Report.error())).isEqualTo(ESC + "]7501;state=error:app=jk" + ST);
        assertThat(ProgramStatus.osc7501(Report.done())).isEqualTo(ESC + "]7501;state=done:app=jk" + ST);
        assertThat(ProgramStatus.osc7501(Report.idle())).isEqualTo(ESC + "]7501;state=idle:app=jk" + ST);
        assertThat(ProgramStatus.osc94(Report.error())).isEqualTo(ESC + "]9;4;2" + BEL);
        assertThat(ProgramStatus.osc94(Report.done())).isEqualTo(ESC + "]9;4;0" + BEL);
        assertThat(ProgramStatus.osc94(Report.idle())).isEqualTo(ESC + "]9;4;0" + BEL);
    }

    @Test
    void progress_and_kind_appear_only_where_the_spec_reads_them() {
        Report doneWithLeftovers = new Report(ProgramStatus.State.DONE, 50, Waiting.AUTH, null, null, null);
        assertThat(ProgramStatus.osc7501(doneWithLeftovers))
                .doesNotContain("progress")
                .doesNotContain("kind");
        Report workingWithKind = new Report(ProgramStatus.State.WORKING, -1, Waiting.AUTH, null, null, null);
        assertThat(ProgramStatus.osc7501(workingWithKind)).doesNotContain("kind");
    }

    @Test
    void every_report_carries_app_jk_and_a_clear_carries_only_its_id() {
        for (Report r : new Report[] {
            Report.working(),
            Report.working(3),
            Report.blocked(Waiting.AUTH),
            Report.error(),
            Report.done(),
            Report.idle(),
            Report.working().child("build/test", "Tests")
        }) {
            assertThat(ProgramStatus.osc7501(r)).contains(":app=jk");
        }
        assertThat(ProgramStatus.osc7501(Report.clear("build"))).isEqualTo(ESC + "]7501;state=clear:id=build" + ST);
    }

    @Test
    void child_records_have_no_taskbar_form() {
        assertThat(ProgramStatus.osc94(Report.working(10).child("build", null))).isEmpty();
        assertThat(ProgramStatus.osc94(Report.clear("build"))).isEmpty();
    }

    @Test
    void an_id_outside_the_grammar_is_refused() {
        assertThatThrownBy(() -> ProgramStatus.osc7501(Report.working().child("a:b", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProgramStatus.osc7501(Report.working().child("x".repeat(33), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProgramStatus.osc7501(Report.clear("a/b/c/d/e/f/g/h/i")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void msg_and_title_are_base64_of_one_line() {
        String seq = ProgramStatus.osc7501(
                Report.working().withMsg("Building acme:api").child("build", "Build ✓"));
        assertThat(decoded(seq, "msg")).isEqualTo("Building acme:api");
        assertThat(decoded(seq, "title")).isEqualTo("Build ✓");
        // The value set has no ':' or ';', so the encoded text never splits a pair.
        assertThat(value(seq, "msg")).matches("[A-Za-z0-9+/=]+");
    }

    @Test
    void control_characters_never_reach_the_decoded_text() {
        String seq = ProgramStatus.osc7501(Report.error().withMsg("line one\nline two\ttab\u0007bell\u009b c1\u007f"));
        String msg = decoded(seq, "msg");
        assertThat(msg).isEqualTo("line one line two tab bell  c1");
        assertThat(msg.codePoints()).noneMatch(ProgramStatus::isControl);
    }

    @Test
    void msg_and_title_are_cut_to_their_decoded_limits_on_a_character_boundary() {
        String longMsg = "é".repeat(2000); // 4000 bytes of UTF-8
        String msg = decoded(ProgramStatus.osc7501(Report.working().withMsg(longMsg)), "msg");
        assertThat(msg.getBytes(StandardCharsets.UTF_8)).hasSize(ProgramStatus.MSG_MAX_BYTES);
        assertThat(msg).isEqualTo("é".repeat(1024));

        String longTitle = "a" + "€".repeat(100); // 1 + 300 bytes
        String title = decoded(ProgramStatus.osc7501(Report.working().child("build", longTitle)), "title");
        assertThat(title.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(ProgramStatus.TITLE_MAX_BYTES);
        assertThat(title).isEqualTo("a" + "€".repeat(63)); // 190 bytes; a 64th would make 193
    }

    @Test
    void the_largest_report_stays_inside_the_sequence_limit() {
        Report big = new Report(
                ProgramStatus.State.BLOCKED, 99, Waiting.PERMISSION, "a".repeat(32), "t".repeat(500), "m".repeat(5000));
        String seq = ProgramStatus.osc7501(big);
        assertThat(seq.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
        assertThat(value(seq, "msg").length()).isLessThanOrEqualTo(2732);
        assertThat(value(seq, "title").length()).isLessThanOrEqualTo(256);
    }

    @Test
    void the_probe_writes_the_query_then_the_device_attributes_request() {
        assertThat(ProgramStatus.QUERY).isEqualTo(ESC + "]7501;?" + ST);
        assertThat(ProgramStatus.DEVICE_ATTRIBUTES).isEqualTo(ESC + "[c");
    }

    @Test
    void the_query_reply_first_means_supported() {
        String reply = ESC + "]7501;?:states=idle,working,done,blocked,error" + ST + ESC + "[?62;22c";
        assertThat(ProgramStatus.answer(reply)).isEqualTo(Answer.SUPPORTED);
        assertThat(ProgramStatus.answeredAttributes(reply)).isTrue();
    }

    @Test
    void the_device_attributes_first_means_unsupported() {
        assertThat(ProgramStatus.answer(ESC + "[?1;2c")).isEqualTo(Answer.UNSUPPORTED);
        // A late 7501 reply after the attributes does not count.
        assertThat(ProgramStatus.answer(ESC + "[?65;1c" + ESC + "]7501;?" + BEL))
                .isEqualTo(Answer.UNSUPPORTED);
    }

    @Test
    void replies_it_does_not_understand_are_ignored() {
        String noise = ESC + "]11;rgb:0000/0000/0000" + BEL + ESC + "[12;40R";
        assertThat(ProgramStatus.answer(noise)).isEqualTo(Answer.PENDING);
        assertThat(ProgramStatus.answer(noise + ESC + "]7501;?" + ST + ESC + "[?62c"))
                .isEqualTo(Answer.SUPPORTED);
        assertThat(ProgramStatus.answer(noise + ESC + "[?62c")).isEqualTo(Answer.UNSUPPORTED);
        assertThat(ProgramStatus.answer("")).isEqualTo(Answer.PENDING);
        assertThat(ProgramStatus.answeredAttributes(noise)).isFalse();
    }

    private static String value(String seq, String key) {
        Matcher m = Pattern.compile("[;:]" + key + "=([^:\u001b]*)").matcher(seq);
        assertThat(m.find()).as(key + " in " + seq).isTrue();
        return m.group(1);
    }

    private static String decoded(String seq, String key) {
        return new String(Base64.getDecoder().decode(value(seq, key)), StandardCharsets.UTF_8);
    }
}
