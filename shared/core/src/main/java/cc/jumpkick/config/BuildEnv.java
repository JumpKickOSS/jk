// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The one way a build-path caller obtains its environment.
 *
 * <p>Resolution, lowest wins to highest:
 *
 * <ol>
 * <li>{@code.env} at the workspace root, then the module — see {@link EnvLookup}
 * <li>the request's {@code clientEnv}, i.e. the shell that ran {@code jk}
 * <li>the engine's own environment, as a last resort
 * </ol>
 *
 * <p>Both halves used to be wrong in different places. {@code.env} was unreachable outside the few
 * sites that had been threaded an environment, and the caller's shell environment was invisible to
 * anything that called {@code System.getenv} inside the engine — a long-lived daemon started from
 * some earlier shell, so {@code FOO=x jk build} had no effect.
 *
 * <p>Both are fixed here rather than by adding a parameter to fifteen call sites, because the
 * request's environment is already on the ambient {@link Session} and every one of those sites
 * already knows its module directory. A helper keyed on the directory alone is therefore enough,
 * and — the point — a <em>new</em> call site gets it right without having to know it exists.
 */
public final class BuildEnv {

    private BuildEnv() {}

    /**
     * The environment for work rooted at {@code moduleDir}.
     *
     * <p>Safe off a request (a unit test, a CLI-side helper): with no session installed the client
     * layer is simply empty and the engine's own environment answers.
     */
    public static UnaryOperator<String> forModule(Path moduleDir) {
        Map<String, String> clientEnv = clientEnv();
        UnaryOperator<String> real = name -> {
            String fromClient = clientEnv.get(name);
            return fromClient != null ? fromClient : System.getenv(name);
        };
        return EnvLookup.forModule(moduleDir, real).asFunction();
    }

    /** The full lookup, for callers that also need {@link EnvLookup#isFromFile} to redact secrets. */
    public static EnvLookup lookupFor(Path moduleDir) {
        Map<String, String> clientEnv = clientEnv();
        return EnvLookup.forModule(moduleDir, name -> {
            String fromClient = clientEnv.get(name);
            return fromClient != null ? fromClient : System.getenv(name);
        });
    }

    /**
     * Redactor for {@code.env}-sourced values at {@code moduleDir}. Use before any
     * free-form text (wire events, journal, errors) leaves the process, and before a value enters
     * a cache key.
     */
    public static SecretRedactor secretsFor(Path moduleDir) {
        if (moduleDir == null) return SecretRedactor.none();
        return SecretRedactor.from(lookupFor(moduleDir));
    }

    private static Map<String, String> clientEnv() {
        try {
            Session session = SessionContext.current();
            return session == null ? Map.of() : session.clientEnv();
        } catch (RuntimeException e) {
            return Map.of(); // no session installed — not a request
        }
    }
}
