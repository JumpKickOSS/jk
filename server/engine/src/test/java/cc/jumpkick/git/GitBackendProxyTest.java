// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.forge.ForgeGitCredentials;
import cc.jumpkick.git.GitBackendsTestSupport.BackendFactory;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.testing.RecordingProxy;
import cc.jumpkick.util.GitProxy;
import java.io.IOException;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A git dependency's remote is reached the way jk reaches any host: through the proxy jk chose for
 * its URL, and direct for a host jk sends direct, even when git's own environment names a proxy.
 * The proxy is a {@link RecordingProxy}, which refuses every request after writing down its host.
 */
@Tag("integration")
class GitBackendProxyTest {

    private static final String PROXIED = "https://git.corp.test/acme/lib.git";
    private static final String DIRECT = "https://git.direct.invalid/acme/lib.git";

    @RegisterExtension
    final RecordingProxy proxy = new RecordingProxy();

    @RegisterExtension
    final RecordingProxy authenticating = new RecordingProxy();

    @TempDir
    Path dir;

    @ParameterizedTest(name = "{0}")
    @MethodSource("cc.jumpkick.git.GitBackendsTestSupport#backends")
    void a_clone_is_a_connect_to_the_proxy_jk_chose_for_the_remote(String name, BackendFactory factory) {
        GitBackend backend = factory.create(dir.resolve("git"), remote -> Optional.of(proxy.url()));

        assertThatThrownBy(() -> backend.fetch(source(PROXIED), false)).isInstanceOf(IOException.class);

        assertThat(proxy.hosts()).as("the hosts the proxy was asked for").contains("git.corp.test");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cc.jumpkick.git.GitBackendsTestSupport#backends")
    void listing_a_remote_s_refs_goes_through_the_proxy_too(String name, BackendFactory factory) {
        GitBackend backend = factory.create(dir.resolve("git"), remote -> Optional.of(proxy.url()));

        assertThatThrownBy(() -> backend.listRefs(source(PROXIED))).isInstanceOf(IOException.class);

        assertThat(proxy.hosts()).contains("git.corp.test");
    }

    @Test
    void a_host_jk_sends_direct_bypasses_the_proxy_git_s_environment_names() throws Exception {
        Optional<GitCliExtension.GitCli> found = GitCliExtension.detect();
        assumeTrue(found.isPresent(), "git command required");
        GitCliExtension.GitCli git = found.get();
        Map<String, String> shell = new LinkedHashMap<>();
        shell.put("PATH", System.getenv("PATH"));
        shell.put("HOME", dir.toString());
        shell.put("https_proxy", proxy.url());

        WorkerEnv.withEngineEnvironment(shell, () -> {
            GitBackend unrouted = new GitCliExtension(dir.resolve("a"), new ForgeGitCredentials(), git, GitProxy.NONE);
            assertThatThrownBy(() -> unrouted.listRefs(source(DIRECT))).isInstanceOf(IOException.class);
            assertThat(proxy.hosts())
                    .as("with no answer from jk, git's own proxy variable decides")
                    .contains("git.direct.invalid");
            int asked = proxy.hosts().size();

            GitBackend direct =
                    new GitCliExtension(dir.resolve("b"), new ForgeGitCredentials(), git, remote -> Optional.of(""));
            assertThatThrownBy(() -> direct.listRefs(source(DIRECT))).isInstanceOf(IOException.class);
            assertThat(proxy.hosts()).as("a host jk sends direct").hasSize(asked);
            return null;
        });
    }

    @Test
    void jgit_connections_take_the_route_of_the_operation_running_on_their_thread() throws Exception {
        URI remote = URI.create(DIRECT);

        assertThat(JGitProxyRoute.route(remote.toURL()))
                .as("outside an operation")
                .isEmpty();
        assertThat(JGitProxyRoute.call(r -> Optional.of(""), () -> JGitProxyRoute.route(remote.toURL()))
                        .map(JGitProxyRoute.Route::proxy))
                .contains(Proxy.NO_PROXY);
        assertThat(JGitProxyRoute.call(GitProxy.NONE, () -> JGitProxyRoute.route(remote.toURL())))
                .as("no answer leaves the choice to JGit")
                .isEmpty();
        Optional<JGitProxyRoute.Route> credentialed = JGitProxyRoute.call(
                r -> Optional.of("http://us%3Aer:se%40cret@proxy.corp:3128"),
                () -> JGitProxyRoute.route(remote.toURL()));
        assertThat(credentialed.map(r -> r.proxy().address().toString())).contains("proxy.corp/<unresolved>:3128");
        PasswordAuthentication credential =
                credentialed.map(JGitProxyRoute.Route::credential).orElseThrow();
        assertThat(credential.getUserName()).isEqualTo("us:er");
        assertThat(new String(credential.getPassword())).isEqualTo("se@cret");
    }

    @Test
    void jgit_answers_a_proxy_s_basic_challenge_with_the_credential_its_url_carries() {
        authenticating.requiringBasic("corp-user", "s3cret");
        String withCredential = authenticating.url().replace("http://", "http://corp-user:s3cret@");
        GitBackend backend =
                new JGitExtension(dir.resolve("git"), new ForgeGitCredentials(), remote -> Optional.of(withCredential));

        assertThatThrownBy(() -> backend.listRefs(source(PROXIED))).isInstanceOf(IOException.class);

        assertThat(authenticating.hosts())
                .as("asked first without, then with the credential")
                .contains("git.corp.test");
        assertThat(authenticating.authorized())
                .as("the CONNECT that answered the 407")
                .contains("git.corp.test");
    }

    @Test
    void jgit_sends_no_credential_to_a_proxy_whose_url_carries_none() {
        authenticating.requiringBasic("corp-user", "s3cret");
        GitBackend backend = new JGitExtension(
                dir.resolve("git"), new ForgeGitCredentials(), remote -> Optional.of(authenticating.url()));

        assertThatThrownBy(() -> backend.listRefs(source(PROXIED))).isInstanceOf(IOException.class);

        assertThat(authenticating.hosts()).contains("git.corp.test");
        assertThat(authenticating.authorized()).isEmpty();
    }

    private static GitSource source(String url) {
        return GitSource.of(url, url, new GitRefSpec.Branch("main"));
    }
}
