// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

/** Error-message hygiene shared by engine verbs and client sync paths. */
public final class Errors {

    private Errors() {}

    /**
     * Human text for {@code t}: its message, or {@code t.toString()} when the message is null or
     * blank — {@code String.valueOf(e.getMessage())} turned a message-less NPE into the literal
     * {@code jk: null} with no class name (JK-2170).
     */
    public static String text(Throwable t) {
        if (t == null) return "unknown error";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.toString() : m;
    }

    /**
     * The one sentence jk uses when it declines to reach the network because the run is offline.
     * {@code target} is the URL, host or coordinate the caller wanted; naming it is the difference
     * between a refusal a user can act on and one they have to guess at.
     *
     * <p>It lives here because the callers do not share anything else: {@code OfflineException}
     * (the transport's typed form, in {@code shared/client-io}) renders this text, and so do the
     * forked plugin workers that bypass jk's HTTP client entirely and can only reach {@code
     * :host}. A refusal should read identically whichever of them produced it.
     */
    public static String offlineRefusal(String target) {
        return "offline: refusing outbound request to " + target
                + " (drop --offline / unset JK_OFFLINE to allow network)";
    }
}
