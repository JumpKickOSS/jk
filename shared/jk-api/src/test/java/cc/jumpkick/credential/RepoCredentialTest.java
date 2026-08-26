// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.credential;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A credential that prints itself prints no secret.
 *
 * <p>Defence in depth, and worth saying which depth. The redactor masks free-form text on its way
 * out of the engine, so a credential concatenated into an event is already covered. These are the
 * paths that never reach a redactor: an exception message, a stack trace, a {@code System.err} in a
 * forked worker, a debugger. On those the record's generated {@code toString} was the leak, and it
 * fired for anything that so much as put the credential in a list.
 *
 * <p>Assertions on the leak are on a boolean, not on the printed text: a {@code doesNotContain}
 * failure quotes both sides, so the test that catches the leak would be the thing that publishes it.
 */
class RepoCredentialTest {

    /** Shaped like a real bearer token so a substring match is meaningful; it is not one. */
    private static final String TOKEN = "ghp-0000-test-only-never-a-real-token";

    private static final String PASSWORD = "test-only-never-a-real-password";

    @Test
    void a_bearer_token_never_prints() {
        RepoCredential bearer = new RepoCredential.Bearer(TOKEN);
        assertThat(bearer.toString().contains(TOKEN))
                .as("Bearer.toString printed its token")
                .isFalse();
        assertThat(bearer.toString()).isEqualTo("Bearer[token=" + RepoCredential.MASK + "]");
    }

    @Test
    void basic_prints_the_username_and_masks_the_password() {
        RepoCredential basic = new RepoCredential.Basic("alice", PASSWORD);
        assertThat(basic.toString().contains(PASSWORD))
                .as("Basic.toString printed its password")
                .isFalse();
        // The username identifies rather than authenticates, and blanking it would blank ordinary
        // build output — the same line the resolver draws when it decides what to file as a secret.
        assertThat(basic.toString()).isEqualTo("Basic[username=alice, password=" + RepoCredential.MASK + "]");
    }

    /**
     * The shapes that actually leaked: nobody calls {@code toString()} on purpose. They concatenate,
     * or they put the credential in a collection and print that.
     */
    @Test
    void the_implicit_print_paths_mask_too() {
        RepoCredential bearer = new RepoCredential.Bearer(TOKEN);
        RepoCredential basic = new RepoCredential.Basic("alice", PASSWORD);

        String concatenated = "publish failed, credential was " + bearer;
        String inACollection = List.of(bearer, basic).toString();
        String inAMap = Map.of("nexus", basic).toString();
        String formatted = String.format("%s / %s", bearer, basic);

        for (String printed : List.of(concatenated, inACollection, inAMap, formatted, String.valueOf(bearer))) {
            assertThat(printed.contains(TOKEN) || printed.contains(PASSWORD))
                    .as("an implicit print path leaked a credential")
                    .isFalse();
            assertThat(printed).contains(RepoCredential.MASK);
        }
    }

    /** Anonymous carries nothing, so there is nothing to hide and nothing to mask. */
    @Test
    void anonymous_prints_plainly_and_has_no_secret() {
        assertThat(RepoCredential.ANONYMOUS.toString()).isEqualTo("Anonymous[]");
        assertThat(RepoCredential.ANONYMOUS.secret()).isNull();
        assertThat(RepoCredential.ANONYMOUS.isAnonymous()).isTrue();
    }

    /**
     * Masking the print must not mask the value: {@link RepoCredential#secret} is what the resolver
     * and the engine's publish decode file with the redactor, and a credential that stopped
     * returning its own secret would silently stop being masked everywhere else.
     */
    @Test
    void the_secret_half_is_still_readable_by_the_code_that_files_it() {
        assertThat(new RepoCredential.Bearer(TOKEN).secret()).isEqualTo(TOKEN);
        assertThat(new RepoCredential.Basic("alice", PASSWORD).secret()).isEqualTo(PASSWORD);
        // Not the username: it is a name, and treating it as a secret blanks ordinary output.
        assertThat(new RepoCredential.Basic("alice", PASSWORD).secret()).isNotEqualTo("alice");
        assertThat(new RepoCredential.Basic("alice", "").secret()).isEmpty();
    }

    /** Records still compare by value — masking is a display concern, not an identity one. */
    @Test
    void masking_does_not_disturb_equality() {
        assertThat(new RepoCredential.Bearer(TOKEN)).isEqualTo(new RepoCredential.Bearer(TOKEN));
        assertThat(new RepoCredential.Bearer(TOKEN)).isNotEqualTo(new RepoCredential.Bearer(TOKEN + "x"));
        assertThat(new RepoCredential.Basic("alice", PASSWORD))
                .isNotEqualTo(new RepoCredential.Basic("alice", PASSWORD + "x"));
    }
}
