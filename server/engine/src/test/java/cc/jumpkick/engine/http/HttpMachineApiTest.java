// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What a request to the machine API may name. */
class HttpMachineApiTest {

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
