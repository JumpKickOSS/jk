// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The environment a build sees: {@code .env} files layered under the real environment (JK-1270).
 *
 * <h2>Precedence</h2>
 *
 * Lowest wins to highest:
 *
 * <ol>
 *   <li>{@code .env} at the workspace root
 *   <li>{@code .env} in the module
 *   <li>the real environment (the caller's, per JK-1269 — not the engine's)
 * </ol>
 *
 * <p><b>The real environment beats {@code .env}</b>, which is what Node's dotenv and Docker Compose
 * both do: {@code .env} supplies defaults, so {@code FOO=x jk build} and a CI variable override the
 * file without anyone editing it. The reverse would make CI overrides impossible to express.
 *
 * <h2>Search roots</h2>
 *
 * Workspace root and module only — deliberately <b>not</b> upward to a git root. Git is not a jk
 * concept: a build must behave the same from a tarball as from a checkout, and a monorepo holding
 * several unrelated workspaces would otherwise cross-contaminate. The workspace root already is the
 * "whole project" scope.
 *
 * <h2>Secrets</h2>
 *
 * A {@code .env} is where tokens live, so {@link #isFromFile} / {@link #secretValues} identify
 * file-sourced values. {@link SecretRedactor#from(EnvLookup)} masks them in free-form text (JSONL,
 * journal, errors) and hashes them for cache keys (JK-1274).
 */
public final class EnvLookup {

    /** {@code .env} — no {@code .env.{profile}} yet; profiles already exist and the overlap needs a design. */
    public static final String FILE_NAME = ".env";

    private final Map<String, String> fromFiles;
    private final UnaryOperator<String> realEnv;

    private EnvLookup(Map<String, String> fromFiles, UnaryOperator<String> realEnv) {
        this.fromFiles = Map.copyOf(fromFiles);
        this.realEnv = realEnv;
    }

    /**
     * Resolve for {@code moduleDir}, layering the workspace root's {@code .env} then the module's
     * under {@code realEnv}.
     *
     * @param realEnv the caller's environment — {@code Inputs.env()} on the build path, never
     *     {@code System::getenv} directly from the engine (JK-1269)
     */
    public static EnvLookup forModule(Path moduleDir, UnaryOperator<String> realEnv) {
        Map<String, String> layered = new LinkedHashMap<>();
        workspaceRoot(moduleDir).ifPresent(root -> {
            if (!root.equals(moduleDir)) layered.putAll(readCached(root.resolve(FILE_NAME)));
        });
        layered.putAll(readCached(moduleDir.resolve(FILE_NAME))); // module wins over workspace
        return new EnvLookup(layered, realEnv);
    }

    /** One cached {@code .env} parse, invalidated by (size, mtime). */
    private record CachedEnv(long size, long mtime, Map<String, String> values) {}

    private static final java.util.concurrent.ConcurrentHashMap<Path, CachedEnv> READ_MEMO =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * {@link DotEnv#read} behind a freshness memo. Redaction resolves the lookup for every output
     * line that leaves the engine (JK-1274), so an uncached read here is two file reads per line of
     * build output (JK-1308). A missing file costs one stat and is never cached.
     */
    private static Map<String, String> readCached(Path file) {
        Path key = file.toAbsolutePath().normalize();
        try {
            var attrs = java.nio.file.Files.readAttributes(
                    key, java.nio.file.attribute.BasicFileAttributes.class);
            long size = attrs.size();
            long mtime = attrs.lastModifiedTime().toMillis();
            CachedEnv hit = READ_MEMO.get(key);
            if (hit != null && hit.size() == size && hit.mtime() == mtime) return hit.values();
            Map<String, String> parsed = DotEnv.read(key);
            if (READ_MEMO.size() > 256) READ_MEMO.clear(); // tiny working set; crude bound is fine
            READ_MEMO.put(key, new CachedEnv(size, mtime, parsed));
            return parsed;
        } catch (java.io.IOException e) {
            return Map.of(); // missing/unreadable → empty, exactly like DotEnv.read
        }
    }

    /** A lookup over {@code .env} values only — for tests and for callers with no real environment. */
    static EnvLookup of(Map<String, String> fromFiles) {
        return new EnvLookup(fromFiles, name -> null);
    }

    /** The value for {@code name}: the real environment if set, else {@code .env}, else null. */
    public String get(String name) {
        String real = realEnv.apply(name);
        return real != null ? real : fromFiles.get(name);
    }

    /** This lookup as the function {@link JkBuildParser#parse(Path, UnaryOperator)} expects. */
    public UnaryOperator<String> asFunction() {
        return this::get;
    }

    /**
     * True when {@code name}'s effective value came from a {@code .env} file rather than the real
     * environment — i.e. it should be treated as a secret.
     */
    public boolean isFromFile(String name) {
        return realEnv.apply(name) == null && fromFiles.containsKey(name);
    }

    /** Every name a {@code .env} file contributed, whether or not the real environment shadows it. */
    public java.util.Set<String> fileNames() {
        return fromFiles.keySet();
    }

    /**
     * Effective values that came from a {@code .env} file (JK-1274). Empty / null values are
     * skipped. Use {@link SecretRedactor#from(EnvLookup)} for redaction and cache-key hashing.
     */
    public java.util.Set<String> secretValues() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String name : fromFiles.keySet()) {
            if (!isFromFile(name)) continue;
            String v = fromFiles.get(name);
            if (v != null && !v.isEmpty()) out.add(v);
        }
        return java.util.Set.copyOf(out);
    }

    private static Optional<Path> workspaceRoot(Path moduleDir) {
        try {
            return WorkspaceLocator.findRoot(moduleDir);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
