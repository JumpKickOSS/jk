// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** A malformed escape is a {@link HttpRequests.BadRequest}, which the dispatcher answers with 400. */
class HttpQueryTest {

    @Test
    void a_param_decodes_once_and_keeps_plus() {
        assertThat(HttpQuery.queryParam("a=1&dir=%2Fx+y%2520", "dir")).isEqualTo("/x+y%20");
        assertThat(HttpQuery.params("a=%41&b")).containsEntry("a", "A").containsEntry("b", "");
    }

    @Test
    void a_malformed_escape_is_a_bad_request() {
        assertThatThrownBy(() -> HttpQuery.queryParam("dir=%zz", "dir")).isInstanceOf(HttpRequests.BadRequest.class);
        assertThatThrownBy(() -> HttpQuery.params("dir=%2")).isInstanceOf(HttpRequests.BadRequest.class);
        assertThat(HttpQuery.queryParamLenient("dir=%zz", "dir")).isNull();
    }
}
