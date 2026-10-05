// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import cc.jumpkick.compat.DistMirrors;
import cc.jumpkick.compat.DownloadOrigin;
import cc.jumpkick.compat.DownloadOrigins;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.http.Http;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.util.JkDirs;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Where Node and its packages come from. The distribution is {@link DistMirrors.Dist#NODE} ({@code
 * [mirrors] node}). The npm registry is, first to answer: {@value #REGISTRY_ENV}, {@code [node]
 * registry} in {@code ~/.jk/config.toml}, a settings.xml {@code <mirror>} whose {@code mirrorOf}
 * names {@code npm}, then {@value #NPM_REGISTRY}; {@code [node.scopes]} maps an {@code @scope} to
 * its own registry. Credentials come from the repository credential chain ({@link DownloadOrigins}).
 */
public final class NodeSources {

    public static final String NODEJS_DIST = DistMirrors.Dist.NODE.publicBase();
    public static final String NPM_REGISTRY = "https://registry.npmjs.org/";
    public static final String DIST_MIRROR_ENV = DistMirrors.Dist.NODE.env();
    public static final String REGISTRY_ENV = "JK_NODE_REGISTRY";

    /** A settings.xml {@code mirrorOf} token that names the npm registry. */
    static final String MIRROR_OF_REGISTRY = "npm";

    private NodeSources() {}

    /** What the origins are read from; {@link #current} is the machine's. */
    record Inputs(
            Function<String, @Nullable String> env,
            GlobalConfig.NodeSources config,
            Map<String, String> mirrors,
            MavenSettings settings) {

        static Inputs current() {
            return new Inputs(JkDirs::env, GlobalConfig.nodeSources(), GlobalConfig.mirrors(), MavenSettings.current());
        }
    }

    public static URI distBase() {
        return dist().url();
    }

    public static URI registry() {
        return npmRegistry().url();
    }

    public static DownloadOrigin dist() {
        return dist(Inputs.current());
    }

    public static DownloadOrigin npmRegistry() {
        return npmRegistry(Inputs.current());
    }

    /** {@code [node.scopes]}: each {@code @scope} and its registry. */
    public static Map<String, DownloadOrigin> scopes() {
        return scopes(Inputs.current());
    }

    static DownloadOrigin dist(Inputs in) {
        return DistMirrors.origin(DistMirrors.Dist.NODE, in.env(), in.mirrors(), in.settings());
    }

    static DownloadOrigin npmRegistry(Inputs in) {
        return DownloadOrigins.resolve(
                in.env(),
                in.settings(),
                REGISTRY_ENV,
                in.config().registry(),
                "[node] registry",
                List.of(MIRROR_OF_REGISTRY),
                NPM_REGISTRY);
    }

    static Map<String, DownloadOrigin> scopes(Inputs in) {
        Map<String, DownloadOrigin> out = new LinkedHashMap<>();
        in.config().scopes().forEach((scope, url) -> {
            URI uri =
                    DownloadOrigin.directory(DownloadOrigins.expand(in.env(), url, "[node.scopes] \"" + scope + "\""));
            out.put(scope, DownloadOrigin.ofHost(uri));
        });
        return out;
    }

    /** Every origin a node download or install may reach: the distribution, the registry and each scope's. */
    public static List<DownloadOrigin> origins() {
        Inputs in = Inputs.current();
        List<DownloadOrigin> out = new ArrayList<>();
        out.add(dist(in));
        out.add(npmRegistry(in));
        out.addAll(scopes(in).values());
        return out;
    }

    /** A client for node downloads: each request to a node origin carries that origin's credential. */
    public static Http http() {
        return new Http().withAuthorization(DownloadOrigins.authorization(origins()));
    }
}
