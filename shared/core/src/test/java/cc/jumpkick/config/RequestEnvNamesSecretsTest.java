// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The client forwards the engine what it reads for itself, and a CI server's, a cloud SDK's or a
 * tool's secrets are not among it: those prefixes are named one by one or not at all (JK-7A19A).
 */
class RequestEnvNamesSecretsTest {

    /** Secrets that live under a prefix the client once forwarded whole, and that nothing in the engine reads. */
    private static final List<String> UNREAD_SECRETS = List.of(
            "GITHUB_APP_PRIVATE_KEY",
            "GITHUB_PAT",
            "GITLAB_PRIVATE_TOKEN",
            "GITLAB_REGISTRY_PASSWORD",
            "BITBUCKET_APP_PASSWORD",
            "CODEBUILD_WEBHOOK_SECRET",
            "JENKINS_API_TOKEN",
            "TEAMCITY_AUTH_TOKEN",
            "WOODPECKER_AGENT_SECRET",
            "BUILDKITE_AGENT_ACCESS_TOKEN",
            "BUILDKITEAGENT_TOKEN",
            "CIRCLECI_CLI_TOKEN",
            "TRAVIS_API_TOKEN",
            "DRONE_TOKEN",
            "TF_BUILD_SECRET",
            "HOMEBREW_GITHUB_API_TOKEN",
            "NVM_AUTH_HEADER",
            "CLAUDE_API_KEY",
            "CLAUDE_CODE_OAUTH_TOKEN",
            "CODEX_API_KEY",
            "TESTCONTAINERS_HUB_PASSWORD",
            "DOCKER_AUTH_CONFIG",
            "DOCKER_PASSWORD",
            "GOOGLE_APPLICATION_CREDENTIALS",
            "GOOGLE_API_KEY",
            "CLOUDSDK_AUTH_ACCESS_TOKEN_FILE",
            "AZURE_CLIENT_SECRET",
            "KUBERNETES_TOKEN",
            "AWS_WEB_IDENTITY_TOKEN_FILE",
            "SSH_PASSPHRASE",
            "GPG_PASSPHRASE",
            "SDKMAN_OFFLINE_TOKEN");

    @Test
    void a_secret_under_a_ci_server_tool_or_cloud_prefix_is_not_forwarded() {
        for (String secret : UNREAD_SECRETS) {
            assertThat(RequestEnvNames.engineReads(secret, false)).as(secret).isFalse();
            assertThat(RequestEnvNames.engineReads(secret, true))
                    .as(secret + " on Windows")
                    .isFalse();
        }
    }

    @Test
    void what_the_engine_reads_by_name_still_rides() {
        for (String name : List.of(
                "GITHUB_ACTIONS",
                "GITHUB_RUN_ID",
                "BUILDKITE",
                "CIRCLECI",
                "GITHUB_TOKEN",
                "GH_TOKEN",
                "GITLAB_TOKEN",
                "AWS_SECRET_ACCESS_KEY",
                "AWS_PROFILE",
                "SSH_AUTH_SOCK",
                "NVM_DIR",
                "HOMEBREW_CELLAR",
                "DOCKER_HOST",
                "TESTCONTAINERS_RYUK_DISABLED",
                "GIT_SSH_COMMAND",
                "JAVA_HOME",
                "JK_REPO_CENTRAL_TOKEN")) {
            assertThat(RequestEnvNames.engineReads(name, false)).as(name).isTrue();
        }
    }

    /** A forwarded name that reads as a credential is one the engine's resolvers are known to read. */
    @Test
    void every_forwarded_name_that_reads_as_a_credential_is_a_stated_credential() {
        assertThat(RequestEnvNames.NAMES).noneMatch(BuildEnv::looksSecret);
        assertThat(RequestEnvNames.CI_RUN).noneMatch(BuildEnv::looksSecret);
        assertThat(RequestEnvNames.CREDENTIALS).isNotEmpty().allMatch(n -> RequestEnvNames.engineReads(n, false));
    }

    @Test
    void every_prefix_states_its_reader_and_none_is_a_ci_server_cloud_or_agent() {
        assertThat(RequestEnvNames.PREFIX_READERS)
                .allSatisfy(p -> assertThat(p.readBy()).as(p.prefix()).isNotBlank());
        assertThat(RequestEnvNames.PREFIXES)
                .doesNotContain(
                        "GITHUB_",
                        "GITLAB_",
                        "BITBUCKET_",
                        "CODEBUILD_",
                        "JENKINS_",
                        "TEAMCITY_",
                        "TF_BUILD",
                        "WOODPECKER_",
                        "BUILDKITE",
                        "CIRCLECI",
                        "TRAVIS",
                        "DRONE",
                        "HOMEBREW_",
                        "NVM_",
                        "CLAUDE",
                        "CODEX_",
                        "TESTCONTAINERS_",
                        "AWS_",
                        "GOOGLE_",
                        "CLOUDSDK_",
                        "AZURE_",
                        "DOCKER_",
                        "KUBERNETES_",
                        "SSH_",
                        "GPG_");
    }
}
