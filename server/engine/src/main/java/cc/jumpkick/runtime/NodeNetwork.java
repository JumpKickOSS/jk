// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.ProxyEnvironment;
import cc.jumpkick.node.NodeSources;
import cc.jumpkick.node.PackageManager;
import cc.jumpkick.util.OwnerOnlyFiles;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * How a node step's package manager reaches the network: the proxy as each manager reads it, and
 * the registry, scopes and credentials {@link NodeSources} resolves, in a jk-owned npm user config
 * written for the one run and deleted after it. bun reads no npm user config, so it gets the same
 * settings as a global {@code .bunfig.toml} in a jk-owned global config directory. The project's own
 * {@code .npmrc}, {@code bunfig.toml} and {@code .yarnrc.yml} are never touched and still apply.
 */
final class NodeNetwork {

    /** File-name prefix of the per-run npm user config, which {@link #discard} deletes. */
    static final String USERCONFIG_PREFIX = "jk-npmrc-";

    static final String USERCONFIG = "npm_config_userconfig";

    /** Directory-name prefix of the per-run bun config home, which {@link #discard} deletes. */
    static final String BUN_CONFIG_PREFIX = "jk-bun-";

    /** The variable that moves bun's global config directory. */
    static final String BUN_CONFIG_HOME = "XDG_CONFIG_HOME";

    private NodeNetwork() {}

    /** What the network settings resolve to; {@link #current} is the machine's. */
    record Sources(
            NodeSources.Origin registry,
            Map<String, NodeSources.Origin> scopes,
            Function<NodeSources.Origin, RepoCredential> credentials,
            Function<URI, Optional<String>> proxyUrl,
            List<String> noProxy,
            @Nullable Path userNpmrc) {

        static Sources current() {
            String home = System.getProperty("user.home");
            ProxyEnvironment proxies = ProxyEnvironment.ambient();
            return new Sources(
                    NodeSources.npmRegistry(),
                    NodeSources.scopes(),
                    NodeSources::credential,
                    proxies::proxyUrl,
                    proxies.noProxyHosts(),
                    home == null ? null : Path.of(home, ".npmrc"));
        }
    }

    /**
     * The variables a step of {@code manager} gets, writing the run's user config under {@code
     * workDir} when one is needed.
     */
    static Map<String, String> env(Path workDir, PackageManager manager) throws IOException {
        return env(Sources.current(), workDir, manager);
    }

    static Map<String, String> env(Sources sources, Path workDir, PackageManager manager) throws IOException {
        Map<String, String> vars = new LinkedHashMap<>(
                proxy(sources.proxyUrl(), sources.noProxy(), sources.registry().url()));
        URI registry = sources.registry().url();
        boolean defaultRegistry = registry.toString().equals(NodeSources.NPM_REGISTRY);
        Optional<String> auth = NodeSources.header(sources.credentials().apply(sources.registry()));
        if (!defaultRegistry) {
            vars.put("YARN_NPM_REGISTRY_SERVER", trimSlash(registry));
            vars.put("NPM_CONFIG_REGISTRY", registry.toString());
            // Berry refuses a plain-http registry whose host it was not told to trust.
            if ("http".equals(registry.getScheme()) && registry.getHost() != null) {
                vars.put("YARN_UNSAFE_HTTP_WHITELIST", registry.getHost());
            }
        }
        auth.ifPresent(header -> {
            if (header.startsWith("Bearer ")) vars.put("YARN_NPM_AUTH_TOKEN", header.substring("Bearer ".length()));
            else vars.put("YARN_NPM_AUTH_IDENT", basicIdent(header));
            // Berry sends a credential on reads only when told to; npm sends a configured one always.
            vars.put("YARN_NPM_ALWAYS_AUTH", "true");
        });
        StringBuilder npmrc = new StringBuilder();
        if (!defaultRegistry) npmrc.append("registry=").append(registry).append('\n');
        auth.ifPresent(header -> npmrc.append(authLine(registry, header)));
        sources.scopes().forEach((scope, origin) -> {
            npmrc.append(scope).append(":registry=").append(origin.url()).append('\n');
            NodeSources.header(sources.credentials().apply(origin))
                    .ifPresent(header -> npmrc.append(authLine(origin.url(), header)));
        });
        if (npmrc.isEmpty()) return vars;
        if (manager == PackageManager.BUN) {
            vars.put(
                    BUN_CONFIG_HOME,
                    bunConfigHome(sources, workDir, defaultRegistry, auth).toString());
            return vars;
        }
        Files.createDirectories(workDir);
        Path file = workDir.resolve(USERCONFIG_PREFIX + UUID.randomUUID());
        String own = "";
        if (sources.userNpmrc() != null && Files.isRegularFile(sources.userNpmrc())) {
            own = Files.readString(sources.userNpmrc());
            if (!own.isEmpty() && !own.endsWith("\n")) own += "\n";
        }
        OwnerOnlyFiles.writeString(file, own + npmrc);
        vars.put(USERCONFIG, file.toString());
        return vars;
    }

    /**
     * A config home holding a {@code .bunfig.toml} with the registry, its credential and the scopes,
     * which bun reads as its global config; the project's own {@code bunfig.toml} still wins over it.
     */
    private static Path bunConfigHome(Sources sources, Path workDir, boolean defaultRegistry, Optional<String> auth)
            throws IOException {
        StringBuilder toml = new StringBuilder("[install]\n");
        if (!defaultRegistry || auth.isPresent()) {
            toml.append("registry = ")
                    .append(bunRegistry(sources.registry().url(), auth))
                    .append('\n');
        }
        if (!sources.scopes().isEmpty()) {
            toml.append("\n[install.scopes]\n");
            sources.scopes().forEach((scope, origin) -> toml.append(quoted(scope.startsWith("@") ? scope : "@" + scope))
                    .append(" = ")
                    .append(bunRegistry(
                            origin.url(),
                            NodeSources.header(sources.credentials().apply(origin))))
                    .append('\n'));
        }
        Path home = workDir.resolve(BUN_CONFIG_PREFIX + UUID.randomUUID());
        Files.createDirectories(home);
        OwnerOnlyFiles.writeString(home.resolve(".bunfig.toml"), toml.toString());
        return home;
    }

    /** bun's inline registry: its url and a token, or a username and password. */
    private static String bunRegistry(URI url, Optional<String> auth) {
        StringBuilder entry = new StringBuilder("{ url = ").append(quoted(url.toString()));
        auth.ifPresent(header -> {
            if (header.startsWith("Bearer ")) {
                entry.append(", token = ").append(quoted(header.substring("Bearer ".length())));
            } else {
                String ident = basicIdent(header);
                int colon = ident.indexOf(':');
                entry.append(", username = ").append(quoted(colon < 0 ? ident : ident.substring(0, colon)));
                if (colon >= 0) entry.append(", password = ").append(quoted(ident.substring(colon + 1)));
            }
        });
        return entry.append(" }").toString();
    }

    private static String quoted(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * The proxy variables for a registry at {@code registry}: npm's and pnpm's {@code npm_config_*},
     * Yarn Berry's {@code YARN_*}, and the standard ones bun reads. Empty when requests go direct.
     */
    static Map<String, String> proxy(Function<URI, Optional<String>> proxyUrl, List<String> noProxy, URI registry) {
        Map<String, String> vars = new LinkedHashMap<>();
        String host = registry.getHost();
        if (host == null) return vars;
        Optional<String> http = proxyUrl.apply(URI.create("http://" + host + "/"));
        Optional<String> https = proxyUrl.apply(URI.create("https://" + host + "/"));
        http.ifPresent(p -> {
            vars.put("npm_config_proxy", p);
            vars.put("YARN_HTTP_PROXY", p);
            vars.put("HTTP_PROXY", p);
        });
        https.ifPresent(p -> {
            vars.put("npm_config_https_proxy", p);
            vars.put("YARN_HTTPS_PROXY", p);
            vars.put("HTTPS_PROXY", p);
        });
        if (!vars.isEmpty() && !noProxy.isEmpty()) {
            String list = String.join(",", noProxy);
            vars.put("npm_config_noproxy", list);
            vars.put("NO_PROXY", list);
        }
        return vars;
    }

    /** {@code //host/path/:_authToken=…} or {@code :_auth=…}: npm's per-registry credential line. */
    static String authLine(URI registry, String header) {
        String prefix = "//" + registry.getAuthority() + registry.getPath();
        if (header.startsWith("Bearer ")) return prefix + ":_authToken=" + header.substring("Bearer ".length()) + "\n";
        return prefix + ":_auth=" + header.substring("Basic ".length()) + "\n";
    }

    private static String basicIdent(String header) {
        return new String(Base64.getDecoder().decode(header.substring("Basic ".length())), StandardCharsets.UTF_8);
    }

    private static String trimSlash(URI url) {
        String s = url.toString();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * The registry and scopes a step's key reads: changing where packages come from re-runs the
     * install. Never a credential.
     */
    static Map<String, String> keyed() {
        return keyed(NodeSources.npmRegistry(), NodeSources.scopes());
    }

    static Map<String, String> keyed(NodeSources.Origin registryOrigin, Map<String, NodeSources.Origin> scopes) {
        Map<String, String> vars = new LinkedHashMap<>();
        URI registry = registryOrigin.url();
        if (!registry.toString().equals(NodeSources.NPM_REGISTRY)) vars.put("JK_NODE_REGISTRY", registry.toString());
        if (!scopes.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            scopes.forEach((scope, origin) ->
                    sb.append(scope).append('=').append(origin.url()).append(';'));
            vars.put("JK_NODE_SCOPES", sb.toString());
        }
        return vars;
    }

    /** Delete the run's user config and bun config home {@code env} names, if jk wrote them. */
    static void discard(Map<String, String> env) {
        String path = env.get(USERCONFIG);
        if (path != null && owned(Path.of(path), USERCONFIG_PREFIX)) delete(Path.of(path));
        String bun = env.get(BUN_CONFIG_HOME);
        if (bun != null && owned(Path.of(bun), BUN_CONFIG_PREFIX)) {
            delete(Path.of(bun).resolve(".bunfig.toml"));
            delete(Path.of(bun));
        }
    }

    private static boolean owned(Path file, String prefix) {
        return file.getFileName() != null && file.getFileName().toString().startsWith(prefix);
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // the next run writes its own; a stale one is owner-only
        }
    }
}
