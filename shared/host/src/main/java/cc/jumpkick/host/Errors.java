// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Error-message hygiene shared by engine verbs and client sync paths. */
public final class Errors {

    private Errors() {}

    /**
     * {@code t} and its causes, outermost first; empty for null. A chain that leads back to a
     * throwable already in it ends there, so a cyclic chain is walked once.
     */
    public static List<Throwable> chain(@Nullable Throwable t) {
        List<Throwable> out = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) out.add(c);
        return out;
    }

    /** The outermost {@code type} in {@code t}'s cause chain, or null. */
    public static <X extends Throwable> @Nullable X inChain(@Nullable Throwable t, Class<X> type) {
        for (Throwable c : chain(t)) {
            if (type.isInstance(c)) return type.cast(c);
        }
        return null;
    }

    /** The innermost cause of {@code t}: {@code t} itself when it has none. */
    public static Throwable rootCause(Throwable t) {
        List<Throwable> chain = chain(t);
        return chain.get(chain.size() - 1);
    }

    /**
     * Human text for {@code t}: its message, or {@code t.toString()} when the message is null or
     * blank — {@code String.valueOf(e.getMessage())} turned a message-less NPE into the literal
     * {@code jk: null} with no class name.
     */
    public static String text(@Nullable Throwable t) {
        if (t == null) return "unknown error";
        String m = t.getMessage();
        if (m == null || m.isBlank()) return t.toString();
        // A file-system exception's message is only the path; the type is what says what happened.
        return t instanceof FileSystemException ? t.getClass().getSimpleName() + ": " + m : m;
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
        return "offline: refusing outbound request to " + target + "; drop --offline or unset JK_OFFLINE";
    }
}
