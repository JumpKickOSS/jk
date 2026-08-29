// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

/**
 * JumpKick-owned JVMs prefer the IPv4 stack for sockets.
 *
 * <p>On Linux dual-stack HotSpot, binding {@code 127.0.0.1} often publishes as
 * {@code [::ffff:127.0.0.1]} — an IPv6 socket with an IPv4-mapped address. WSL2 localhost
 * forwarding only relays real IPv4 loopback listeners, so Windows {@code http://127.0.0.1:…}
 * connection-refuses while the same URL works inside the distro. Setting
 * {@code java.net.preferIPv4Stack=true} forces AF_INET sockets.
 *
 * <p>Call {@link #install()} at process entry before any networking, and pass {@link #JVM_FLAG} on
 * every jk-spawned JVM line (engine, workers, AOT trainer) so child processes inherit the same
 * policy. User {@code jk run} / {@code jk tool} JVMs are out of scope.
 */
public final class PreferIpv4 {

    /** System property name HotSpot reads for IPv4-only sockets. */
    public static final String PROPERTY = "java.net.preferIPv4Stack";

    /** Spawn-line form; must match dump-time and runtime flags when an AOT cache is involved. */
    public static final String JVM_FLAG = "-D" + PROPERTY + "=true";

    private PreferIpv4() {}

    /** Idempotent: set the property so subsequent sockets use IPv4. */
    public static void install() {
        System.setProperty(PROPERTY, "true");
    }
}
