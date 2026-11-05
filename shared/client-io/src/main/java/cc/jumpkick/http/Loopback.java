// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import cc.jumpkick.model.RepositorySpec;

/**
 * The host names that mean this machine. Deliberately loopback only, not private ranges: a
 * corporate repository on {@code 10.x} is a real shared host and is treated like any other.
 */
final class Loopback {

    private Loopback() {}

    /** Whether {@code host} (a URI host, IPv6 literals bracketed or not) is this machine: {@link RepositorySpec#loopback}. */
    static boolean is(String host) {
        return RepositorySpec.loopback(host);
    }

    /** Whether the {@code host:port} authority names this machine: {@link RepositorySpec#loopbackAuthority}. */
    static boolean authority(String authority) {
        return RepositorySpec.loopbackAuthority(authority);
    }
}
