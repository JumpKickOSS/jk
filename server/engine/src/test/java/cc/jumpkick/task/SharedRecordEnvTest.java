// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.host.Hashing;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The form of a record that may leave the machine names the environment variables its key read and
 * withholds their values, digests included: a digest of a short secret is a brute force away.
 */
class SharedRecordEnvTest {

    @Test
    void the_shared_form_keeps_env_names_and_no_digest_of_a_value() {
        String key = "k".repeat(64);
        String pin = Hashing.sha256Hex("1234");
        String plugin = Hashing.sha256Hex("hunter2");
        String inherited = Hashing.sha256Hex("PATH=/usr/bin");
        Map<String, String> inputs = Map.of(
                "src/A.java", "aaa",
                "env", "env:DB_PIN=" + pin + ",env:REGION=" + Hashing.sha256Hex("eu"),
                "inputs",
                        "classes:dir:abc;plugin-env:TOKEN=" + plugin + ";inherited-env:" + inherited
                                + ";test-env:MODE=fast");
        String local = ActionRecordText.render(
                "compile-main@x",
                key,
                inputs,
                Map.of("A.class", "a".repeat(64)),
                Set.of(),
                List.of(),
                ActionRecordText.Meta.now(
                        "java:compile", ActionRecordText.Writes.OUTPUTS, "jk/test", "linux-x86_64", -1, Map.of()));

        String shared = ActionRecordText.renderShared(ActionRecordText.parse(local, key));

        assertThat(local).contains(pin);
        assertThat(shared)
                .doesNotContain(pin)
                .doesNotContain(plugin)
                .doesNotContain(inherited)
                .doesNotContain("MODE=fast")
                .contains("env:DB_PIN=-")
                .contains("env:REGION=-")
                .contains("plugin-env:TOKEN=-")
                .contains("inherited-env:-")
                .contains("test-env:MODE=-")
                .contains("classes:dir:abc");
        assertThat(ActionRecordText.parse(shared, key).inputs()).containsEntry("src/A.java", "aaa");
    }

    /**
     * The names behind a suite's listing and whole-environment tokens, which its record keeps so a
     * miss can name the variable that came or went, stay on this host, and so does the listing's
     * own digest.
     */
    @Test
    void the_shared_form_leaves_out_the_callers_variable_names_behind_a_listing() {
        String key = "k".repeat(64);
        Map<String, String> inputs = Map.of(
                "x:env-names", "n".repeat(64),
                "env-name:MY_APP_FOO", "",
                "env-value:MY_APP_BAR", "v".repeat(64));
        String local = ActionRecordText.render(
                "run-tests@x",
                key,
                inputs,
                Map.of(),
                Set.of(),
                List.of(),
                ActionRecordText.Meta.now(
                        "java:run-tests", ActionRecordText.Writes.VERDICT, "jk/test", "linux-x86_64", -1, Map.of()));

        String shared = ActionRecordText.renderShared(ActionRecordText.parse(local, key));

        assertThat(local).contains("MY_APP_FOO").contains("MY_APP_BAR");
        assertThat(shared)
                .doesNotContain("MY_APP_FOO")
                .doesNotContain("MY_APP_BAR")
                .doesNotContain("v".repeat(64))
                .as("the listing's digest is withheld like every other: one combined key leaves, nothing per name")
                .doesNotContain("n".repeat(64))
                .contains("INPUT - x:env-names\n");
    }
    /**
     * A run-tests record as jk 0.52 wrote it for a module whose {@code [test] env} forwards an unset
     * variable and sets literals (the dojo-4 study's writer, jk-cli and jk-client-io): the sentinel
     * holds U+0000, the literals stand as the values, and a forwarded value's digest sits under a
     * name that kept its word. Its shared form names each variable and holds none of that (JK-ANWXB).
     */
    @Test
    void the_shared_form_of_a_suite_record_names_its_test_env_and_holds_no_value_and_no_nul() {
        String key = "k".repeat(64);
        String digest = Hashing.sha256Hex("unix:path=/run/user/1000/bus");
        String local = String.join(
                "\n",
                "TASK run-tests@093fa723c276",
                "KEY " + key,
                "FORMAT 2",
                "KIND java:run-tests",
                "WRITES test-marker",
                "CREATED 2026-11-18T09:22:28Z",
                "PRODUCER jk/0.52.1-SNAPSHOT#10+05fd08b5e362",
                "HOST macos-aarch64",
                "INPUT 6e39be448abf7901b9c95a164da25743450d826b3b29440810a6597333aa5f99 cp",
                "INPUT \u0000absent x:test-env:ANDROID_HOME",
                "INPUT false x:test-env:CI",
                "INPUT tcp x:test-env:JK_ENGINE_TRANSPORT",
                "INPUT xterm-256color x:test-env:TERM",
                "INPUT  x:test-env:NO_COLOR",
                "INPUT " + digest + " x:test-env:DBUS_SESSION_BUS_ADDRESS=sha256",
                "INPUT unset x:test-env:GRAALVM_HOME",
                "INPUT " + Hashing.sha256Hex("1") + " x:env-read:MY_APP_PIN",
                "INPUT " + Hashing.sha256Hex("v") + " x:profile-env:MODE",
                "INPUT  test-stamp-v4",
                "OUTPUT 0 tests.failed",
                "OUTPUT 15 tests.succeeded",
                "OUTPUT 15 tests.total",
                "END 3",
                "");

        String shared = ActionRecordText.renderShared(ActionRecordText.parse(local, key));

        assertThat(shared)
                .doesNotContain("\u0000")
                .doesNotContain(digest)
                .doesNotContain(Hashing.sha256Hex("1"))
                .doesNotContain(Hashing.sha256Hex("v"))
                .doesNotContain("xterm-256color")
                .doesNotContain(" tcp ")
                .doesNotContain("INPUT false")
                .contains("INPUT - x:test-env:ANDROID_HOME\n")
                .contains("INPUT - x:test-env:CI\n")
                .contains("INPUT - x:test-env:NO_COLOR\n")
                .contains("INPUT - x:test-env:DBUS_SESSION_BUS_ADDRESS\n")
                .contains("INPUT - x:test-env:GRAALVM_HOME\n")
                .contains("INPUT - x:env-read:MY_APP_PIN\n")
                .contains("INPUT - x:profile-env:MODE\n")
                .contains("x:test-env:TERM")
                .contains("test-stamp-v4")
                .contains("6e39be448abf7901b9c95a164da25743450d826b3b29440810a6597333aa5f99 cp");
        assertThat(ActionRecordText.parse(shared, key).actionKey()).isEqualTo(key);
    }
}
