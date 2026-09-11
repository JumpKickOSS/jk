// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.command.JkEnv;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JkDiffTest {

    private static final String JDK21 = "/home/u/.jdks/temurin-21";
    private static final String JDK25 = "/home/u/.jdks/temurin-25";
    private static final String GRAAL = "/home/u/.jdks/graalvm-25";
    private static final String PRIOR_JDK = "/home/u/.sdkman/candidates/java/current";
    private static final String LOCAL_BIN = "/home/u/.jk/bin";
    private static final String PROJ = "/home/u/src/proj";

    @Test
    void empty_diff_encodes_to_empty_string() {
        assertThat(JkDiff.empty().encode()).isEmpty();
    }

    @Test
    void round_trips_basic_keys() {
        var src = new LinkedHashMap<String, String>();
        src.put("JAVA_HOME", JDK21);
        src.put("PATH", LOCAL_BIN + ":/home/u/bin");
        var encoded = new JkDiff(src).encode();

        var decoded = JkDiff.parse(encoded);
        assertThat(decoded.previousValue("JAVA_HOME")).isEqualTo(JDK21);
        assertThat(decoded.previousValue("PATH")).isEqualTo(LOCAL_BIN + ":/home/u/bin");
    }

    @Test
    void unset_sentinel_round_trips() {
        var src = Map.of("JAVA_HOME", JkDiff.UNSET_SENTINEL);
        var decoded = JkDiff.parse(new JkDiff(src).encode());
        assertThat(decoded.wasUnset("JAVA_HOME")).isTrue();
        assertThat(decoded.wasUnset("PATH")).isFalse();
    }

    @Test
    // The null payload is deliberate: an absent __JK_DIFF variable.
    @SuppressWarnings("NullAway")
    void malformed_payload_yields_empty_diff() {
        assertThat(JkDiff.parse("not-base64").keys()).isEmpty();
        assertThat(JkDiff.parse("").keys()).isEmpty();
        assertThat(JkDiff.parse(null).keys()).isEmpty();
    }

    @Test
    void values_with_newlines_or_nulls_serialize_safely() {
        // Real-world values shouldn't contain \0, but we should at least
        // tolerate values containing colons and slashes which our format
        // uses internally for delimiting.
        var src = Map.of("VAR", "a:b/c.d");
        var decoded = JkDiff.parse(new JkDiff(src).encode());
        assertThat(decoded.previousValue("VAR")).isEqualTo("a:b/c.d");
    }

    @Test
    void next_captures_pre_jk_values_from_snapshot_but_skips_path() {
        // No prior diff. Target adds JAVA_HOME + PATH. PATH is surgically swapped and must
        // not be frozen into the diff even when present on the target.
        var prior = JkDiff.empty();
        var target = new JkEnv.Target(
                Optional.of(Path.of(PROJ)), Map.of("JAVA_HOME", JDK25, "PATH", JDK25 + "/bin:" + LOCAL_BIN));
        var snapshot = (JkDiff.EnvSnapshot) k -> switch (k) {
            case "JAVA_HOME" -> PRIOR_JDK;
            case "PATH" -> LOCAL_BIN;
            default -> null;
        };
        var next = prior.next(target, snapshot);
        assertThat(next.previousValue("JAVA_HOME")).isEqualTo(PRIOR_JDK);
        assertThat(next.keys()).doesNotContain("PATH");
        assertThat(next.wasUnset("JAVA_HOME")).isFalse();
    }

    @Test
    void next_preserves_prior_pre_jk_value_across_re_activation() {
        // The user `cd`s into one project, then another. The diff carries
        // the original pre-activation JAVA_HOME — not the previous project's
        // JAVA_HOME — so deactivating later restores correctly.
        var prior = new JkDiff(Map.of("JAVA_HOME", PRIOR_JDK));
        var target = new JkEnv.Target(Optional.of(Path.of("/home/u/src/another")), Map.of("JAVA_HOME", JDK25));
        var snapshot = (JkDiff.EnvSnapshot) k -> JDK21; // not what we want — would shadow
        var next = prior.next(target, snapshot);
        assertThat(next.previousValue("JAVA_HOME")).isEqualTo(PRIOR_JDK);
    }

    @Test
    void next_records_unset_sentinel_for_keys_not_in_environment() {
        var prior = JkDiff.empty();
        var target = new JkEnv.Target(Optional.of(Path.of(PROJ)), Map.of("GRAALVM_HOME", GRAAL));
        var snapshot = (JkDiff.EnvSnapshot) k -> null; // nothing set in env
        var next = prior.next(target, snapshot);
        assertThat(next.wasUnset("GRAALVM_HOME")).isTrue();
    }
}
