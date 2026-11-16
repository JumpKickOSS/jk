// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The machine API's own checks: which pages may post to it, and what a request may name. */
class HttpMachineApiTest {

    @Test
    void only_this_listeners_own_origin_or_none_may_post() {
        assertThat(HttpMachineApi.sameOrigin(null, 8910)).isTrue();
        assertThat(HttpMachineApi.sameOrigin("http://127.0.0.1:8910", 8910)).isTrue();
        assertThat(HttpMachineApi.sameOrigin("http://localhost:8910", 8910)).isTrue();
        for (String other : new String[] {
            "http://evil.example",
            "http://evil.example:8910",
            "https://127.0.0.1:8910",
            "http://127.0.0.1:8911",
            "http://127.0.0.1",
            "null",
            "http://127.0.0.1:8910/path",
            "http://user@127.0.0.1:8910",
            "file://",
            "not a uri at all"
        }) {
            assertThat(HttpMachineApi.sameOrigin(other, 8910)).as(other).isFalse();
        }
    }

    @Test
    void a_request_names_one_to_a_few_distros_as_strings() {
        assertThat(HttpMachineApi.names("{\"distros\":[\"Ubuntu-26.10\",\" Debian \"]}"))
                .containsExactly("Ubuntu-26.10", "Debian");
        assertThat(HttpMachineApi.names("{\"distros\":[]}")).isNull();
        assertThat(HttpMachineApi.names("{\"distros\":\"Ubuntu\"}")).isNull();
        assertThat(HttpMachineApi.names("{\"distros\":[1]}")).isNull();
        assertThat(HttpMachineApi.names("{\"distros\":[\"\"]}")).isNull();
        assertThat(HttpMachineApi.names("[]")).isNull();
        assertThat(HttpMachineApi.names("not json")).isNull();
        StringBuilder many = new StringBuilder("{\"distros\":[");
        for (int i = 0; i < 33; i++)
            many.append(i == 0 ? "" : ",").append("\"d").append(i).append('"');
        assertThat(HttpMachineApi.names(many.append("]}").toString())).isNull();
    }
}
