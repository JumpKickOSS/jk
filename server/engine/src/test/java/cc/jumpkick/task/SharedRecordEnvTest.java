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
}
