// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Errors;
import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * {@code jk audit --offline} refuses in the CLI (saving an engine round trip) and in the auditor
 * worker (web/MCP triggers). One decision, one wording owner — {@code Errors.offlineRefusal} —
 * whichever side produced it. The worker half is pinned by {@code AuditorOfflineTest}; this pins
 * the CLI half to the same owner.
 */
class AuditCommandOfflineTest {

    @Test
    void the_refusal_is_the_owner_wording_over_a_stable_phrase_by_default() {
        assertThat(AuditCommand.offlineRefusal(null))
                .isEqualTo(Errors.offlineRefusal("the OSV API"))
                .contains("offline: refusing outbound request to");
    }

    /** An explicit override is the one endpoint the CLI actually knows; the refusal names it. */
    @Test
    void an_explicit_batch_url_is_named_in_the_refusal() {
        assertThat(AuditCommand.offlineRefusal(URI.create("https://osv.example/v1/querybatch")))
                .isEqualTo(Errors.offlineRefusal("https://osv.example/v1/querybatch"))
                .contains("https://osv.example/v1/querybatch");
    }
}
