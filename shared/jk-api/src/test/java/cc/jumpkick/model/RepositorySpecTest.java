// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The written form of a repository URL: one spelling per origin, so two manifests agree by text. */
class RepositorySpecTest {

    @Test
    void the_normalized_url_lower_cases_scheme_and_host_drops_the_default_port_and_ends_in_one_slash() {
        assertThat(RepositorySpec.normalizedUrl(URI.create("HTTPS://Packages.Confluent.io:443/maven")))
                .hasToString("https://packages.confluent.io/maven/");
        assertThat(RepositorySpec.normalizedUrl(URI.create("http://nexus.example:8081/repository/releases//")))
                .hasToString("http://nexus.example:8081/repository/releases/");
        assertThat(RepositorySpec.normalizedUrl(URI.create("https://repo.example")))
                .hasToString("https://repo.example/");
    }

    @Test
    void a_url_already_in_the_written_form_is_returned_unchanged() {
        URI written = URI.create("https://repo.example/m2/");
        assertThat(RepositorySpec.normalizedUrl(written)).isSameAs(written);
    }

    @Test
    void an_opaque_or_hostless_url_is_left_as_declared() {
        URI s3 = URI.create("s3:bucket/path");
        assertThat(RepositorySpec.normalizedUrl(s3)).isSameAs(s3);
        URI file = URI.create("file:///srv/repo");
        assertThat(RepositorySpec.normalizedUrl(file)).hasToString("file:///srv/repo/");
    }

    @Test
    void two_specs_are_one_repository_when_their_normalized_urls_agree() {
        RepositorySpec a = new RepositorySpec("r", URI.create("https://repo.example/m2"));
        RepositorySpec b = new RepositorySpec("r", URI.create("https://REPO.example/m2/"));
        assertThat(a.sameRepository(b)).isTrue();
        assertThat(a.sameRepository(new RepositorySpec("r", URI.create("https://repo.example/m3/"))))
                .isFalse();
    }

    /** The origin a credential is scoped to: scheme, host and port as the join normalizes them; the path is the repository. */
    @Test
    void two_urls_are_one_origin_by_scheme_host_and_port_after_normalization() {
        URI a = URI.create("https://Repo.example.com/x");
        assertThat(RepositorySpec.sameOrigin(a, URI.create("https://repo.example.com:443/y/")))
                .isTrue();
        assertThat(RepositorySpec.sameOrigin(a, URI.create("HTTPS://repo.example.com/x")))
                .isTrue();
        assertThat(RepositorySpec.sameOrigin(a, URI.create("https://repo.example.com:8443/y")))
                .isFalse();
        assertThat(RepositorySpec.sameOrigin(a, URI.create("http://repo.example.com/x")))
                .isFalse();
        assertThat(RepositorySpec.sameOrigin(a, URI.create("https://cdn.example.com/x")))
                .isFalse();
    }

    @Test
    void a_hostless_url_is_one_origin_only_with_its_own_normalized_spelling() {
        assertThat(RepositorySpec.sameOrigin(URI.create("s3:bucket/path"), URI.create("s3:bucket/path")))
                .isTrue();
        assertThat(RepositorySpec.sameOrigin(URI.create("s3:bucket/path"), URI.create("s3:other/path")))
                .isFalse();
        assertThat(RepositorySpec.sameOrigin(URI.create("file:///srv/repo"), URI.create("file:///srv/repo/")))
                .isTrue();
        assertThat(RepositorySpec.sameOrigin(URI.create("file:///srv/repo"), URI.create("file:///srv/other")))
                .isFalse();
        assertThat(RepositorySpec.sameOrigin(URI.create("file:///srv/repo"), URI.create("https://repo.example/")))
                .isFalse();
    }

    @Test
    void an_authority_is_judged_by_its_host_with_or_without_a_port() {
        for (String authority : List.of(
                "localhost",
                "localhost:8081",
                "127.0.0.1:8081",
                "[::1]:8081",
                "[::1]",
                "::1",
                "::ffff:127.0.0.1",
                "[::ffff:7f00:1]:443")) {
            assertThat(RepositorySpec.loopbackAuthority(authority))
                    .as(authority)
                    .isTrue();
        }
        for (String authority :
                List.of("", "repo.example.com:443", "10.0.0.5:8081", "[::ffff:10.0.0.5]:8081", "127.example.com:80")) {
            assertThat(RepositorySpec.loopbackAuthority(authority))
                    .as(authority)
                    .isFalse();
        }
        assertThat(RepositorySpec.loopbackAuthority(null)).isFalse();
    }

    @Test
    void loopback_is_decided_by_spelling_and_covers_every_form_of_127_8_and_the_ipv6_loopback() {
        for (String host : List.of(
                "localhost",
                "LOCALHOST",
                "127.0.0.1",
                "127.1.2.3",
                "127.255.255.254",
                "::1",
                "[::1]",
                "0:0:0:0:0:0:0:1",
                "[0:0:0:0:0:0:0:1]",
                "::ffff:127.0.0.1",
                "[::ffff:127.0.0.1]",
                "[::FFFF:127.0.0.1]",
                "::ffff:7f00:1",
                "[::ffff:7f00:1]",
                "[::ffff:7f12:3456]",
                "0:0:0:0:0:ffff:127.0.0.1")) {
            assertThat(RepositorySpec.loopback(host)).as(host).isTrue();
        }
        for (String host : List.of(
                "",
                "10.0.0.1",
                "128.0.0.1",
                "::2",
                "[::ffff:10.0.0.1]",
                "::ffff:a00:1",
                "[::ffff:c0a8:101]",
                "::ffff:8000:1",
                "127.example.com",
                "localhost.example.com",
                "repo.maven.apache.org",
                "[::1",
                "not a host")) {
            assertThat(RepositorySpec.loopback(host)).as(host).isFalse();
        }
        assertThat(RepositorySpec.loopback(null)).isFalse();
    }
}
