// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The CI run's variables reach the engine by exact name; a CI server's secrets never do by that path. */
class RequestEnvNamesCiRunTest {

    private static final List<String> SECRET_WORDS = List.of("TOKEN", "PASSWORD", "SECRET", "KEY");

    @Test
    void no_ci_run_name_names_a_credential() {
        assertThat(RequestEnvNames.CI_RUN).allSatisfy(name -> assertThat(SECRET_WORDS)
                .noneMatch(word -> name.toUpperCase(Locale.ROOT).contains(word)));
        assertThat(RequestEnvNames.CI_RUN).allMatch(name -> RequestEnvNames.engineReads(name, false));
    }

    @Test
    void gitlab_and_circleci_secrets_are_not_forwarded() {
        for (String secret : List.of(
                "CI_JOB_TOKEN",
                "CI_REGISTRY_PASSWORD",
                "CI_DEPLOY_PASSWORD",
                "CI_DEPLOY_USER",
                "CI_JOB_JWT_V2",
                "CI_SECRET_KEY",
                "CIRCLE_TOKEN",
                "CIRCLE_OIDC_TOKEN",
                "CIRCLE_PROJECT_SECRET")) {
            assertThat(RequestEnvNames.engineReads(secret, false)).as(secret).isFalse();
            assertThat(RequestEnvNames.engineReads(secret, true))
                    .as(secret + " on Windows")
                    .isFalse();
        }
    }
}
