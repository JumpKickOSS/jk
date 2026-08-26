// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import java.net.URI;
import java.util.Locale;

/**
 * A URI rendered safe to print, and a URI safe to record.
 *
 * <p>Two ways a credential rides a URL into somewhere it should never be:
 *
 * <ul>
 * <li><b>userinfo</b> — {@code https://alice:s3cr3t@nexus.example.com/repo/}. The JDK's {@code
 * HttpClient} does not authenticate from userinfo (it needs an {@code Authenticator} or an explicit
 * {@code Authorization} header), so in jk the credential half is inert on the wire and live
 * everywhere the URL is printed, journalled, or written to {@code jk-lock.toml}.
 * <li><b>query parameters</b> — an OAuth {@code code} or {@code client_secret}, or a bare
 * {@code ?token=…} on a registry URL.
 * </ul>
 *
 * <p>{@link #forMessage} is for display text (exceptions, logs, the journal). {@link
 * #withoutUserInfo} is for a URL that goes on being used — it drops the credential and keeps
 * everything a request needs.
 *
 * <p>Both work on the URI's own {@code toString()} rather than re-assembling from decoded
 * components, so percent-encoding survives untouched: the output of {@link #withoutUserInfo} is the
 * input minus a prefix of its authority, never a re-encoding of it.
 */
public final class SafeUri {

    /** What a credential becomes. Same three asterisks {@code SecretRedactor} uses. */
    public static final String MASK = "***";

    private SafeUri() {}

    /**
     * {@code uri} with any {@code user:password@} prefix removed from its authority. Returns the
     * same instance when there is none, which is the overwhelmingly common case.
     */
    public static URI withoutUserInfo(URI uri) {
        if (uri == null || uri.getRawUserInfo() == null) return uri;
        return URI.create(stripUserInfo(uri.toString()));
    }

    /**
     * {@code uri} as display text: no userinfo, and {@code token} / {@code code} /
     * {@code client_secret} (and any {@code *_token} / {@code *_secret} / {@code *password}) query
     * values replaced by {@link #MASK}. A null URI renders as {@code "null"} so a message never
     * depends on the caller null-checking first.
     */
    public static String forMessage(URI uri) {
        if (uri == null) return "null";
        return maskQuery(stripUserInfo(uri.toString()));
    }

    /** Everything from the authority's start up to and including a {@code '@'}, removed. */
    private static String stripUserInfo(String uri) {
        int authority = uri.indexOf("://");
        if (authority < 0) return uri;
        authority += 3;
        int end = uri.length();
        for (int i = authority; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        int at = uri.lastIndexOf('@', end - 1);
        return at < authority ? uri : uri.substring(0, authority) + uri.substring(at + 1);
    }

    /** Each {@code name=value} of the query string, with credential-named values masked. */
    private static String maskQuery(String uri) {
        int start = uri.indexOf('?');
        if (start < 0) return uri;
        int end = uri.indexOf('#', start);
        if (end < 0) end = uri.length();
        StringBuilder out = new StringBuilder(uri.length()).append(uri, 0, start + 1);
        for (int from = start + 1; from <= end; ) {
            int amp = uri.indexOf('&', from);
            int stop = amp < 0 || amp > end ? end : amp;
            int eq = uri.indexOf('=', from);
            if (eq > from && eq < stop && isCredentialParam(uri.substring(from, eq))) {
                out.append(uri, from, eq + 1).append(MASK);
            } else {
                out.append(uri, from, stop);
            }
            if (stop == end) break;
            out.append('&');
            from = stop + 1;
        }
        return out.append(uri, end, uri.length()).toString();
    }

    /**
     * Names whose value is a credential. The three the OAuth device flow and the package registries
     * actually use, plus the suffix forms ({@code access_token}, {@code refresh_token}, …) that are
     * the same secret under a longer name.
     */
    private static boolean isCredentialParam(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("token")
                || n.equals("code")
                || n.equals("client_secret")
                || n.endsWith("_token")
                || n.endsWith("_secret")
                || n.endsWith("password");
    }
}
