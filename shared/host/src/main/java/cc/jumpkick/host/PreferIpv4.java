// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

/**
 * JumpKick-owned JVMs prefer the IPv4 stack for sockets — as a <em>launch flag only</em>.
 *
 * <p>On Linux dual-stack HotSpot, binding {@code 127.0.0.1} often publishes as
 * {@code [::ffff:127.0.0.1]} — an IPv6 socket with an IPv4-mapped address. WSL2 localhost
 * forwarding only relays real IPv4 loopback listeners, so Windows {@code http://127.0.0.1:…}
 * connection-refuses while the same URL works inside the distro. Setting
 * {@code java.net.preferIPv4Stack=true} forces AF_INET sockets.
 *
 * <p>Pass {@link #JVM_FLAG} on every jk-spawned JVM line that serves a listener (engine, workers,
 * AOT trainer). Never {@code System.setProperty} it at runtime: the JDK reads the property from
 * several places at different times, and a late set leaves networking incoherent — on Windows
 * JDK 25, {@code InetAddress.getLoopbackAddress()} then answers {@code ::1} while listeners bind
 * {@code 127.0.0.1}, so a client refuses connections to its own healthy engine. A JVM launched by
 * something other than jk (a {@code JK_ENGINE_EXE} wrapper) must carry the flag itself. User
 * {@code jk run} / {@code jk tool} JVMs are out of scope.
 */
public final class PreferIpv4 {

    /** System property name HotSpot reads for IPv4-only sockets. */
    public static final String PROPERTY = "java.net.preferIPv4Stack";

    /** Spawn-line form; must match dump-time and runtime flags when an AOT cache is involved. */
    public static final String JVM_FLAG = "-D" + PROPERTY + "=true";

    private PreferIpv4() {}
}
