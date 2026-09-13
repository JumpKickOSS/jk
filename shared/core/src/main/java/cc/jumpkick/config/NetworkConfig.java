// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * The {@code [network]} table of {@code ~/.jk/config.toml}: the proxy jk's HTTP goes through.
 *
 * <pre>{@code
 * [network]
 * proxy = "http://proxy.corp:3128"          # every request; user:password@ for Basic
 * https-proxy = "http://proxy.corp:3129"    # https targets only, when they differ
 * no-proxy = ["nexus.corp", ".internal.corp", "10.0.0.5:8081"]
 * }</pre>
 *
 * <p>Machine-scoped and lenient like the rest of the file. The shell's {@code http_proxy} /
 * {@code https_proxy} / {@code no_proxy} variables fill in what it leaves unset, and its
 * {@code no-proxy} entries are added to the shell's. The selection itself lives with the HTTP
 * client ({@code ProxyEnvironment}); this is the file's half.
 */
public record NetworkConfig(
        @Nullable String proxy, @Nullable String httpsProxy, List<String> noProxy) {

    public static final NetworkConfig EMPTY = new NetworkConfig(null, null, List.of());

    public NetworkConfig {
        noProxy = noProxy == null ? List.of() : List.copyOf(noProxy);
    }

    /**
     * {@code network} read leniently: a missing table or key is unset, and {@code no-proxy} may be
     * an array or the comma-separated string the environment variable takes.
     */
    static NetworkConfig parse(@Nullable TomlTable network) {
        if (network == null) return EMPTY;
        List<String> noProxy = new ArrayList<>();
        switch (network.get("no-proxy")) {
            case TomlArray array -> noProxy.addAll(TomlValues.stringList(network, "no-proxy"));
            case String csv -> {
                for (String entry : csv.split(",")) {
                    if (!entry.isBlank()) noProxy.add(entry.trim());
                }
            }
            case null, default -> {}
        }
        return new NetworkConfig(
                TomlValues.optString(network, "proxy").orElse(null),
                TomlValues.optString(network, "https-proxy").orElse(null),
                noProxy);
    }
}
