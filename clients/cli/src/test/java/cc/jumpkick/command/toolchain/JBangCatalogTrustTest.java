// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.tool.TrustedSources;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A forge alias ({@code alias@user}) falls back from GitHub to GitLab to Bitbucket. Each of those
 * origins is a separate decision: nothing is fetched from one the user has not allowed.
 */
class JBangCatalogTrustTest {

    private static final byte[] CATALOG =
            "{\"aliases\":{\"hello\":{\"script-ref\":\"hello.java\"}}}".getBytes(StandardCharsets.UTF_8);

    @Test
    void an_origin_allowed_once_does_not_let_the_fallback_contact_the_next_forge(@TempDir Path state) throws Exception {
        List<String> asked = new ArrayList<>();
        List<URI> fetched = new ArrayList<>();
        // The user allows GitHub once and declines everything after it; GitHub has no catalog.
        JBangCatalog.Outcome outcome = JBangCatalog.resolve(
                "hello@someone",
                uri -> {
                    fetched.add(uri);
                    return null;
                },
                TrustedSources.load(state),
                origin -> {
                    asked.add(origin);
                    return asked.size() == 1 ? null : 2;
                });

        assertThat(outcome).isEqualTo(new JBangCatalog.Outcome.Refused(2));
        assertThat(asked).containsExactly("https://github.com/someone/", "https://gitlab.com/someone/");
        assertThat(fetched).allMatch(uri -> uri.getHost().equals("raw.githubusercontent.com"));
        assertThat(fetched).hasSize(1);
    }

    @Test
    void each_fallback_origin_is_asked_before_its_fetch(@TempDir Path state) throws Exception {
        List<String> events = new ArrayList<>();
        JBangCatalog.Outcome outcome = JBangCatalog.resolve(
                "hello@someone",
                uri -> {
                    events.add("fetch " + uri.getHost());
                    return uri.getHost().equals("gitlab.com") ? CATALOG : null;
                },
                TrustedSources.load(state),
                origin -> {
                    events.add("ask " + origin);
                    return null;
                });

        assertThat(outcome).isInstanceOf(JBangCatalog.Outcome.Found.class);
        assertThat(((JBangCatalog.Outcome.Found) outcome).resolved().pageOrigin())
                .isEqualTo("https://gitlab.com/someone/");
        assertThat(events)
                .containsExactly(
                        "ask https://github.com/someone/",
                        "fetch raw.githubusercontent.com",
                        "ask https://gitlab.com/someone/",
                        "fetch gitlab.com");
    }

    @Test
    void a_trusted_candidate_keeps_the_others_unasked_and_uncontacted(@TempDir Path state) throws Exception {
        TrustedSources trust = TrustedSources.load(state);
        trust.add("https://bitbucket.org/someone/");
        List<String> fetchedHosts = new ArrayList<>();
        JBangCatalog.Outcome outcome = JBangCatalog.resolve(
                "hello@someone",
                uri -> {
                    fetchedHosts.add(uri.getHost());
                    return CATALOG;
                },
                trust,
                origin -> {
                    throw new AssertionError("asked about " + origin);
                });

        assertThat(outcome).isInstanceOf(JBangCatalog.Outcome.Found.class);
        assertThat(fetchedHosts).containsExactly("bitbucket.org");
    }
}
