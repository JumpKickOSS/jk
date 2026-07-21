// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import java.util.Optional;
import java.util.function.Function;

/**
 * Token for a {@code (provider, host)}: {@code JK_<KIND>_TOKEN} → ecosystem env → native CLI →
 * stored credential → interactive {@link DeviceFlow}. Env/CLI injected for tests.
 */
public final class ForgeAuth {

    private final TokenStore store;
    private final Function<String, String> env;
    private final CliTokenProbe cliProbe;

    public ForgeAuth() {
        this(new TokenStore(), System::getenv, CliTokenProbe.REAL);
    }

    /** Visible for tests — inject the store, env lookup, and CLI probe. */
    public ForgeAuth(TokenStore store, Function<String, String> env, CliTokenProbe cliProbe) {
        this.store = store;
        this.env = env;
        this.cliProbe = cliProbe;
    }

    /**
     * Steps 1–4: resolve a token without ever prompting. Returns empty when nothing is configured and
     * the caller should fall back to an interactive login.
     */
    public Optional<ResolvedToken> resolveSilently(ForgeKind kind, String host) {
        // 1. jk's own override
        String jk = nonBlank(env.apply(kind.jkEnvVar()));
        if (jk != null) return Optional.of(new ResolvedToken(jk, TokenSource.JK_ENV));

        // 2. ecosystem-native env vars
        for (String var : kind.nativeEnvVars()) {
            String v = nonBlank(env.apply(var));
            if (v != null) return Optional.of(new ResolvedToken(v, TokenSource.NATIVE_ENV));
        }

        // 3. native CLI piggyback (gh/glab) — best-effort, never throws
        Optional<String> cli = kind.nativeCliToken().flatMap(cliProbe::token);
        if (cli.isPresent()) return cli.map(t -> new ResolvedToken(t, TokenSource.NATIVE_CLI));

        // 4. jk's stored credential for this host
        return store.read(resolveHost(kind, host)).map(t -> new ResolvedToken(t, TokenSource.STORE));
    }

    /** Persist a freshly-obtained token (device flow or pasted PAT). */
    public void store(ForgeKind kind, String host, String token) {
        store.write(resolveHost(kind, host), token);
    }

    /** Forget jk's stored credential for a host. Leaves env/CLI tokens alone. */
    public void logout(ForgeKind kind, String host) {
        store.clear(resolveHost(kind, host));
    }

    /**
     * Resolve the effective host: explicit value wins, else the provider's default. Providers without
     * a default (Gitea/Forgejo) require one.
     */
    public static String resolveHost(ForgeKind kind, String host) {
        String h = nonBlank(host);
        if (h != null) return ForgeKind.normalizeHost(h);
        return kind.defaultHost()
                .orElseThrow(() -> new AuthException(kind.displayName() + " has no default host — pass --host."));
    }

    private static String nonBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
