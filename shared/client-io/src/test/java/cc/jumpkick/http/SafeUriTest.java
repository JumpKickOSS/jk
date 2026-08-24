// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class SafeUriTest {

    @Test
    void userinfo_never_survives_into_a_message() {
        assertThat(SafeUri.forMessage(URI.create("https://alice:s3cr3t-token@nexus.example.com/repo/a.jar")))
                .isEqualTo("https://nexus.example.com/repo/a.jar");
        assertThat(SafeUri.forMessage(URI.create("https://ghp_deadbeef@maven.pkg.github.com/acme/lib/1.0/lib.jar")))
                .as("a bare token as the whole userinfo, no colon")
                .isEqualTo("https://maven.pkg.github.com/acme/lib/1.0/lib.jar");
        assertThat(SafeUri.forMessage(URI.create("http://alice:pw@127.0.0.1:8081/x")))
                .as("the port is part of the authority and stays")
                .isEqualTo("http://127.0.0.1:8081/x");
    }

    @Test
    void credential_query_values_are_masked_and_the_rest_is_not() {
        assertThat(SafeUri.forMessage(URI.create("https://h/cb?code=abc123&state=xyz")))
                .isEqualTo("https://h/cb?code=***&state=xyz");
        assertThat(SafeUri.forMessage(URI.create("https://h/p?client_secret=shh&client_id=public")))
                .as("client_id is not a secret; client_secret is")
                .isEqualTo("https://h/p?client_secret=***&client_id=public");
        assertThat(SafeUri.forMessage(URI.create("https://h/p?token=t&access_token=a&refresh_token=r")))
                .isEqualTo("https://h/p?token=***&access_token=***&refresh_token=***");
        assertThat(SafeUri.forMessage(URI.create("https://h/p?Token=T")))
                .as("query names are matched case-insensitively")
                .isEqualTo("https://h/p?Token=***");
        assertThat(SafeUri.forMessage(URI.create("https://h/p?a=1&b=2#frag")))
                .as("nothing to mask, fragment intact")
                .isEqualTo("https://h/p?a=1&b=2#frag");
        assertThat(SafeUri.forMessage(URI.create("https://alice:pw@h/p?token=t#f")))
                .as("both passes compose")
                .isEqualTo("https://h/p?token=***#f");
    }

    @Test
    void withoutUserInfo_keeps_a_usable_url_and_its_encoding() {
        URI clean = URI.create("https://nexus.example.com/repo/a%2Bb/1.0/x.jar?q=1");
        assertThat(SafeUri.withoutUserInfo(clean))
                .as("nothing to strip — the same instance, not a re-encoding")
                .isSameAs(clean);

        URI stripped = SafeUri.withoutUserInfo(URI.create("https://alice:pw@nexus.example.com/repo/a%2Bb/x.jar"));
        assertThat(stripped.toString())
                .as("percent-encoding survives: no decode/re-encode round trip")
                .isEqualTo("https://nexus.example.com/repo/a%2Bb/x.jar");
        assertThat(stripped.getUserInfo()).isNull();
        assertThat(stripped.resolve("../y.jar").toString()).isEqualTo("https://nexus.example.com/repo/y.jar");
    }

    @Test
    void shapes_that_have_no_userinfo_to_strip() {
        assertThat(SafeUri.forMessage(null)).isEqualTo("null");
        assertThat(SafeUri.withoutUserInfo(null)).isNull();
        assertThat(SafeUri.forMessage(URI.create("file:///var/tmp/repo/x.jar")))
                .isEqualTo("file:///var/tmp/repo/x.jar");
        assertThat(SafeUri.forMessage(URI.create("s3://bucket/prefix/x.jar"))).isEqualTo("s3://bucket/prefix/x.jar");
        assertThat(SafeUri.forMessage(URI.create("mailto:dev@example.com")))
                .as("an opaque URI has no authority — the '@' is not a userinfo delimiter")
                .isEqualTo("mailto:dev@example.com");
        assertThat(SafeUri.forMessage(URI.create("https://h/path@with@ats")))
                .as("'@' after the authority ends is path text")
                .isEqualTo("https://h/path@with@ats");
    }
}
