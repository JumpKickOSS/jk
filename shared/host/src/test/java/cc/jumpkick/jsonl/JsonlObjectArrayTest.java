// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jsonl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsonlObjectArrayTest {

    @Test
    void splits_an_array_of_objects_at_the_top_level_only() {
        String json = "{\"type\":\"x\",\"rows\":[{\"a\":\"}\",\"n\":{\"deep\":1}},{\"b\":\"[\"}],\"tail\":[]}";
        assertThat(Jsonl.objectArray(json, "rows"))
                .containsExactly("{\"a\":\"}\",\"n\":{\"deep\":1}}", "{\"b\":\"[\"}");
        assertThat(Jsonl.objectArray(json, "tail")).isEmpty();
        assertThat(Jsonl.objectArray(json, "missing")).isEmpty();
        assertThat(Jsonl.objectArray("{\"rows\": [ {\"a\":1} ]}", "rows")).containsExactly("{\"a\":1}");
    }
}
