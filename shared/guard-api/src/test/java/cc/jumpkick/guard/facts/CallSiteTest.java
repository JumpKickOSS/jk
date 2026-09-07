// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CallSiteTest {
    private static final String DIGEST = "java/security/MessageDigest";
    private static final String GET = "getInstance";
    private static final String DESC = "(Ljava/lang/String;)Ljava/security/MessageDigest;";

    @Test
    void the_target_is_the_binary_owner_the_name_and_the_descriptor() {
        CallSite s = new CallSite(DIGEST, GET, DESC, 31, "SHA-256", 1);
        assertThat(s.target()).isEqualTo("java.security.MessageDigest#getInstance" + DESC);
    }

    @Test
    void a_first_sighting_seeds_the_argument_window_with_its_literal_or_nothing() {
        assertThat(new CallSite(DIGEST, GET, DESC, 31, "SHA-256", 1).literals()).containsExactly("SHA-256");
        assertThat(new CallSite(DIGEST, GET, DESC, 31, null, 1).literals()).isEmpty();
        CallSite window = CallSite.first(DIGEST, GET, DESC, 5, "os.name", List.of("os.name", "", "os.name"));
        assertThat(window.literals()).as("distinct, in order").containsExactly("os.name", "");
        assertThat(window.count()).isEqualTo(1);
        assertThat(window.literalBefore()).isEqualTo("os.name");
    }

    @Test
    void merging_keeps_the_first_line_and_literal_and_counts_every_invoke() {
        CallSite first = new CallSite(DIGEST, GET, DESC, 40, "SHA-256", 1);
        CallSite merged = first.merged(12, "MD5");
        assertThat(merged.line()).as("the earliest line").isEqualTo(12);
        assertThat(merged.literalBefore()).as("the first literal seen").isEqualTo("SHA-256");
        assertThat(merged.count()).isEqualTo(2);
        assertThat(merged.literals()).containsExactly("SHA-256", "MD5");
        assertThat(merged.merged(41, "MD5").literals())
                .as("no duplicate literal")
                .containsExactly("SHA-256", "MD5");
        assertThat(merged.merged(41, "MD5").count()).isEqualTo(3);
    }

    @Test
    void a_missing_first_literal_is_filled_by_a_later_one() {
        CallSite first = new CallSite(DIGEST, GET, DESC, 40, null, 1);
        CallSite merged = first.merged(45, "SHA-1");
        assertThat(merged.literalBefore()).isEqualTo("SHA-1");
        assertThat(merged.literals()).containsExactly("SHA-1");
        assertThat(merged.merged(50, null).literalBefore()).isEqualTo("SHA-1");
    }

    @Test
    void an_unknown_line_never_wins_over_a_known_one() {
        CallSite noLine = new CallSite(DIGEST, GET, DESC, 0, null, 1);
        assertThat(noLine.merged(7, null).line()).isEqualTo(7);
        assertThat(new CallSite(DIGEST, GET, DESC, 7, null, 1).merged(0, null).line())
                .isEqualTo(7);
        assertThat(noLine.merged(0, null).line()).isEqualTo(0);
    }

    @Test
    void merging_with_a_window_appends_only_new_literals() {
        CallSite first = CallSite.first(DIGEST, GET, DESC, 5, "a", List.of("a", "b"));
        CallSite merged = first.merged(9, "c", List.of("b", "c", "d"));
        assertThat(merged.literals()).containsExactly("a", "b", "c", "d");
        assertThat(merged.count()).isEqualTo(2);
        assertThat(merged.line()).isEqualTo(5);
        assertThat(merged.owner()).isEqualTo(DIGEST);
        assertThat(merged.name()).isEqualTo(GET);
        assertThat(merged.desc()).isEqualTo(DESC);
    }

    @Test
    void the_literal_list_is_immutable() {
        CallSite s = new CallSite(DIGEST, GET, DESC, 1, "x", 1);
        assertThatThrownBy(() -> s.literals().add("y")).isInstanceOf(UnsupportedOperationException.class);
    }
}
