// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import java.util.Locale;

/**
 * The host names that mean this machine. Deliberately loopback only, not private ranges: a
 * corporate repository on {@code 10.x} is a real shared host and is treated like any other.
 */
final class Loopback {

    private Loopback() {}

    /** Whether {@code host} (a URI host, IPv6 literals bracketed or not) is this machine. */
    static boolean is(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]");
    }

    /** Whether the {@code host:port} authority names this machine. */
    static boolean authority(String authority) {
        int colon = authority.lastIndexOf(':');
        return is(colon < 0 ? authority : authority.substring(0, colon));
    }
}
