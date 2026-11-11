// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.SessionContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A line a test prints is masked of every credential filed for the workspace before anyone sees it. */
class ResultAggregatorSecretsTest {

    @AfterEach
    void forget() {
        ResolvedSecrets.clear();
    }

    @Test
    void a_printed_credential_reaches_the_listener_masked() {
        ResolvedSecrets.recordFor(SessionContext.current().workingDir(), "tok-1234567890");
        List<String> seen = new ArrayList<>();
        ResultAggregator aggregator = new ResultAggregator(
                new TestProgressListener() {
                    @Override
                    public void onUserOutput(int workerId, String line) {
                        seen.add(line);
                    }
                },
                0);

        aggregator.userOutput("token=tok-1234567890");

        assertThat(seen).containsExactly("token=" + SecretRedactor.MASK);
    }
}
