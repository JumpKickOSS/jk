// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

/**
 * For other modules' tests: Central and its mirror as two named hosts reached through a proxy at a
 * loopback server, so a test drives the routing {@link Http} does in production while it sees
 * every request and nothing leaves the machine.
 */
public final class ProxiedCentral {

    private ProxiedCentral() {}

    /**
     * A window under {@code cacheDir} that treats {@code centralHost} as Central and {@code
     * mirrorBase} (no trailing slash) as its mirror.
     */
    public static CentralMirror mirror(Path cacheDir, String centralHost, String mirrorBase) {
        return new CentralMirror(cacheDir, CentralMirror.DEFAULT_WINDOW, true, centralHost, mirrorBase);
    }

    /**
     * An {@link Http} that sends every request through {@code proxy}, routes Central-bound ones by
     * {@code mirror}'s window, and keeps Central's cooldown under {@code cooldownDir}.
     */
    public static Http http(InetSocketAddress proxy, CentralMirror mirror, Path cooldownDir) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .proxy(ProxySelector.of(proxy))
                .build();
        return new Http(client, new Duration[] {Duration.ofMillis(1)}, mirror, new HostCooldown(cooldownDir));
    }
}
