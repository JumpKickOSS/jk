// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
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
    void two_specs_are_the_same_origin_when_their_normalized_urls_agree() {
        RepositorySpec a = new RepositorySpec("r", URI.create("https://repo.example/m2"));
        RepositorySpec b = new RepositorySpec("r", URI.create("https://REPO.example/m2/"));
        assertThat(a.sameOrigin(b)).isTrue();
        assertThat(a.sameOrigin(new RepositorySpec("r", URI.create("https://repo.example/m3/"))))
                .isFalse();
    }
}
