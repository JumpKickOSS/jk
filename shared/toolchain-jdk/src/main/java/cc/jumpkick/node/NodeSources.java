// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.util.JkDirs;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * Where Node comes from: the distribution root ({@code JK_NODE_DIST_MIRROR}, else {@code [node]
 * dist-mirror} in {@code ~/.jk/config.toml}, else {@value #NODEJS_DIST}) and the npm registry the
 * package managers come from ({@code JK_NODE_REGISTRY}, else {@code [node] registry}, else
 * {@value #NPM_REGISTRY}).
 */
public final class NodeSources {

    public static final String NODEJS_DIST = "https://nodejs.org/dist/";
    public static final String NPM_REGISTRY = "https://registry.npmjs.org/";
    public static final String DIST_MIRROR_ENV = "JK_NODE_DIST_MIRROR";
    public static final String REGISTRY_ENV = "JK_NODE_REGISTRY";

    private NodeSources() {}

    public static URI distBase() {
        GlobalConfig.NodeSources config = GlobalConfig.nodeSources();
        return directory(first(JkDirs.env(DIST_MIRROR_ENV), config.distMirror(), NODEJS_DIST));
    }

    public static URI registry() {
        GlobalConfig.NodeSources config = GlobalConfig.nodeSources();
        return directory(first(JkDirs.env(REGISTRY_ENV), config.registry(), NPM_REGISTRY));
    }

    /** {@code url} with a trailing slash, so relative paths resolve beneath it. */
    static URI directory(String url) {
        return URI.create(url.endsWith("/") ? url : url + "/");
    }

    private static String first(@Nullable String env, @Nullable String config, String fallback) {
        if (env != null && !env.isBlank()) return env.trim();
        return config != null ? config : fallback;
    }
}
