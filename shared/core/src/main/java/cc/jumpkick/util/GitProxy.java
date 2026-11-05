// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code http.proxy} git runs with for one remote, so a template catalog's clone and a git
 * dependency's fetch take the route jk's own requests take: a proxy URL, an empty value (git's "no
 * proxy", for a host jk sends direct), or nothing at all, which leaves git's own configuration in
 * charge.
 *
 * <p>The engine answers from the user's {@code [network]}, Maven's {@code settings.xml} and the
 * shell's proxy variables; this module only carries the answer to git.
 */
@FunctionalInterface
public interface GitProxy {

    /** Leaves every remote to git's own configuration. */
    GitProxy NONE = remote -> Optional.empty();

    /** The {@code http.proxy} for an http(s) {@code remote}, or empty to leave git's configuration alone. */
    Optional<String> forRemote(URI remote);

    /**
     * As {@link #forRemote} for a remote as written in a template source or a git dependency: a URL,
     * or a form that is not one ({@code git@host:path}), which git reaches without an http proxy.
     */
    default Optional<String> httpProxy(String remote) {
        URI uri;
        try {
            uri = new URI(remote);
        } catch (URISyntaxException notAUrl) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return Optional.empty();
        }
        return forRemote(uri);
    }

    /**
     * Set {@code http.proxy} to {@code value} in {@code env}, the environment of a git child, through
     * git's {@code GIT_CONFIG_COUNT} / {@code GIT_CONFIG_KEY_n} / {@code GIT_CONFIG_VALUE_n}: after any
     * entries the environment already carries, and never on a command line another user can read.
     * A proxy value also clears {@code no_proxy}: jk applied its own bypass list when it chose the
     * proxy, and a different list in the engine's environment must not overrule that choice.
     */
    static void configure(Map<String, String> env, String value) {
        int index = 0;
        String count = env.get("GIT_CONFIG_COUNT");
        if (count != null) {
            try {
                index = Math.max(0, Integer.parseInt(count.strip()));
            } catch (NumberFormatException unreadable) {
                // git refuses a malformed count itself; start the list over so ours still applies
                index = 0;
            }
        }
        env.put("GIT_CONFIG_KEY_" + index, "http.proxy");
        env.put("GIT_CONFIG_VALUE_" + index, value);
        env.put("GIT_CONFIG_COUNT", Integer.toString(index + 1));
        if (!value.isEmpty()) {
            env.remove("no_proxy");
            env.remove("NO_PROXY");
        }
    }
}
