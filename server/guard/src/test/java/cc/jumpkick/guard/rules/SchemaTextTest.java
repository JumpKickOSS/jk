// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.schema.KeySpec;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.guard.schema.SchemaText;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SchemaTextTest {

    @Test
    void every_kind_renders_its_keys_and_example() {
        for (Kind k : Kind.values()) {
            String card = SchemaText.render(k);
            assertThat(card)
                    .startsWith("kind = \"" + k.id() + "\"")
                    .contains("example:")
                    .contains("[guards.");
            for (KeySpec key : k.keys())
                assertThat(card).as(k.id() + " lists " + key.name()).contains("  " + key.name() + " (");
        }
    }

    @Test
    void every_kind_documents_every_key_and_no_key_is_documented_twice() {
        for (Kind k : Kind.values()) {
            Set<String> seen = new HashSet<>();
            for (KeySpec key : k.keys()) {
                assertThat(key.doc()).as(k.id() + "." + key.name()).isNotBlank();
                assertThat(seen.add(key.name()))
                        .as(k.id() + " repeats " + key.name())
                        .isTrue();
            }
            for (KeySpec common : SchemaText.commonKeys()) {
                assertThat(seen)
                        .as(k.id() + " redefines common key " + common.name())
                        .doesNotContain(common.name());
            }
        }
    }
}
