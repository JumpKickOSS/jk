// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.publish;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The OIDC identity a CI runner offers for keyless signing. A publish signs keylessly only when one
 * is present, so no run ever opens a browser.
 */
public final class CiIdentity {

    private CiIdentity() {}

    /** GitHub Actions: the request URL and its bearer token, both set when the job may mint a token. */
    private static final List<String> GITHUB =
            List.of("ACTIONS_ID_TOKEN_REQUEST_URL", "ACTIONS_ID_TOKEN_REQUEST_TOKEN");

    /** GitLab CI: the identity token a job declares under {@code id_tokens}. */
    private static final String GITLAB = "SIGSTORE_ID_TOKEN";

    /** Every variable a signer reads the identity from, so a forked worker can be handed them. */
    public static final List<String> VARIABLES = List.of(GITHUB.get(0), GITHUB.get(1), GITLAB);

    /** Whether {@code env} holds an identity token, or the means to request one. */
    public static boolean available(Function<String, @Nullable String> env) {
        return GITHUB.stream().allMatch(name -> set(env, name)) || set(env, GITLAB);
    }

    private static boolean set(Function<String, @Nullable String> env, String name) {
        String value = env.apply(name);
        return value != null && !value.isBlank();
    }
}
